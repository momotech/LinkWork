package com.linkwork.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkwork.common.SnowflakeIdGenerator;
import com.linkwork.common.exception.ForbiddenOperationException;
import com.linkwork.config.DispatchConfig;
import com.linkwork.mapper.LinkworkFileMapper;
import com.linkwork.mapper.LinkworkTaskMapper;
import com.linkwork.model.dto.TaskCompleteRequest;
import com.linkwork.model.dto.TaskCreateRequest;
import com.linkwork.model.dto.TaskResponse;
import com.linkwork.model.entity.LinkworkFile;
import com.linkwork.model.entity.LinkworkTask;
import com.linkwork.model.enums.TaskStatus;
import com.linkwork.model.role.RoleRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskV1Service {

    private final LinkworkTaskMapper taskMapper;
    private final LinkworkFileMapper fileMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RoleService roleService;
    private final SnowflakeIdGenerator idGenerator;
    private final DispatchConfig dispatchConfig;
    private final CronJobV1Service cronJobService;
    private final AdminAccessService adminAccessService;

    @Transactional
    public LinkworkTask createTask(TaskCreateRequest request, String creatorId, String creatorName,
                                    String creatorIp, String source, Long cronJobId) {
        String taskNo = idGenerator.nextTaskNo();

        RoleRecord role = roleService.getById(request.getRoleId());
        if (role == null) throw new IllegalArgumentException("Role not found: " + request.getRoleId());

        LinkworkTask task = new LinkworkTask();
        task.setTaskNo(taskNo);
        task.setWorkstationId(request.getRoleId());
        task.setWorkstationName(role.getName());
        task.setPrompt(request.getPrompt());
        task.setStatus(TaskStatus.PENDING);
        task.setSource(normalizeSource(source));
        task.setCronJobId("CRON".equals(task.getSource()) ? cronJobId : null);
        task.setImage(role.getImage() != null ? role.getImage() : "ubuntu-22.04-python3.10");
        task.setSelectedModel(request.getModelId());
        task.setAssemblyId(request.getAssemblyId());
        task.setCreatorId(creatorId);
        task.setCreatorName(creatorName);
        task.setCreatorIp(creatorIp);
        task.setTokensUsed(0);
        task.setDurationMs(0L);
        task.setIsDeleted(0);

        Map<String, Object> configMap = new HashMap<>();
        configMap.put("modelId", request.getModelId());
        if (request.getFileIds() != null && !request.getFileIds().isEmpty()) {
            configMap.put("fileIds", request.getFileIds());
        }
        if (role.getConfigJson() != null && !role.getConfigJson().isEmpty()) {
            configMap.put("mcp", role.getConfigJson().get("mcp"));
            configMap.put("skills", role.getConfigJson().get("skills"));
            configMap.put("knowledge", role.getConfigJson().get("knowledge"));
            configMap.put("gitRepos", role.getConfigJson().get("gitRepos"));
            configMap.put("env", role.getConfigJson().get("env"));
        }
        if (request.getConfigJson() != null) {
            configMap.put("custom", request.getConfigJson());
        }
        try {
            task.setConfigJson(objectMapper.writeValueAsString(configMap));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize task config: " + taskNo, e);
        }

        taskMapper.insert(task);

        String streamKey = dispatchConfig.getLogStreamKey(task.getWorkstationId(), taskNo);
        publishTaskEvent(streamKey, "TASK_CREATED", taskNo, Map.of("message", "Task created"));

        final LinkworkTask dispatchTask = task;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                pushToDispatchQueue(dispatchTask);
            }
        });

        log.info("Task created: taskNo={}, roleId={}, roleName={}, modelId={}",
                taskNo, role.getId(), role.getName(), request.getModelId());
        return task;
    }

    @Transactional
    public LinkworkTask createTask(TaskCreateRequest request, String creatorId, String creatorName) {
        return createTask(request, creatorId, creatorName, null, "MANUAL", null);
    }

    public LinkworkTask getTaskByNo(String taskNo) {
        LambdaQueryWrapper<LinkworkTask> w = new LambdaQueryWrapper<>();
        w.eq(LinkworkTask::getTaskNo, taskNo);
        LinkworkTask task = taskMapper.selectOne(w);
        if (task == null) throw new IllegalArgumentException("Task not found: " + taskNo);
        return task;
    }

    public LinkworkTask getTaskByNo(String taskNo, String creatorId) {
        LinkworkTask task = getTaskByNo(taskNo);
        assertOwner(task, creatorId);
        return task;
    }

    public Page<LinkworkTask> listTasks(Long roleId, String status, Integer page, Integer pageSize, String creatorId) {
        LambdaQueryWrapper<LinkworkTask> w = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(creatorId) && !adminAccessService.isAdmin(creatorId)) {
            w.eq(LinkworkTask::getCreatorId, creatorId);
        }
        if (roleId != null) w.eq(LinkworkTask::getWorkstationId, roleId);
        if (StringUtils.hasText(status)) w.eq(LinkworkTask::getStatus, TaskStatus.valueOf(status.toUpperCase()));
        w.orderByDesc(LinkworkTask::getCreatedAt);
        return taskMapper.selectPage(new Page<>(page, pageSize), w);
    }

    @Transactional
    public LinkworkTask updateStatus(String taskNo, TaskStatus status) {
        LinkworkTask task = getTaskByNo(taskNo);
        task.setStatus(status);
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        cronJobService.onTaskStatusChanged(task, status);
        return task;
    }

    @Transactional
    public LinkworkTask completeTask(String taskNo, TaskCompleteRequest request) {
        LinkworkTask task = getTaskByNo(taskNo);
        if (task.getStatus() != TaskStatus.RUNNING && task.getStatus() != TaskStatus.PENDING) {
            throw new IllegalArgumentException("Task status does not allow completion: " + task.getStatus());
        }

        TaskStatus targetStatus;
        try {
            targetStatus = TaskStatus.valueOf(request.getStatus().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid status: " + request.getStatus());
        }
        if (targetStatus != TaskStatus.COMPLETED && targetStatus != TaskStatus.FAILED) {
            throw new IllegalArgumentException("Completion status must be COMPLETED or FAILED");
        }

        task.setStatus(targetStatus);
        if (request.getTokensUsed() != null) task.setTokensUsed(request.getTokensUsed());
        if (request.getInputTokens() != null) task.setInputTokens(request.getInputTokens());
        if (request.getOutputTokens() != null) task.setOutputTokens(request.getOutputTokens());
        if (request.getRequestCount() != null) task.setRequestCount(request.getRequestCount());
        if (request.getUsagePercent() != null) task.setUsagePercent(request.getUsagePercent());

        Long durationMs = request.getDurationMs();
        if ((durationMs == null || durationMs <= 0) && task.getCreatedAt() != null) {
            durationMs = Math.max(0, Duration.between(task.getCreatedAt(), LocalDateTime.now()).toMillis());
        }
        task.setDurationMs(durationMs);

        if (request.getReportJson() != null) {
            try {
                task.setReportJson(objectMapper.writeValueAsString(request.getReportJson()));
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize report for task: {}", taskNo);
            }
        }

        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        cronJobService.onTaskStatusChanged(task, targetStatus);

        String streamKey = dispatchConfig.getLogStreamKey(task.getWorkstationId(), taskNo);
        String eventType = targetStatus == TaskStatus.COMPLETED ? "TASK_COMPLETED" : "TASK_FAILED";
        publishTaskEvent(streamKey, eventType, taskNo, Map.of(
                "tokens_used", task.getTokensUsed() != null ? task.getTokensUsed() : 0,
                "duration_ms", task.getDurationMs() != null ? task.getDurationMs() : 0));

        log.info("Task completed: taskNo={}, status={}, tokensUsed={}, durationMs={}",
                taskNo, targetStatus, task.getTokensUsed(), task.getDurationMs());
        return task;
    }

    @Transactional
    public LinkworkTask abortTask(String taskNo, String updaterId, String updaterName) {
        LinkworkTask task = StringUtils.hasText(updaterId) ? getTaskByNo(taskNo, updaterId) : getTaskByNo(taskNo);
        if (task.getStatus() != TaskStatus.RUNNING && task.getStatus() != TaskStatus.PENDING) {
            throw new IllegalArgumentException("Task status does not allow abort: " + task.getStatus());
        }

        task.setUpdaterId(updaterId);
        task.setUpdaterName(updaterName);
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);

        String queueKey = dispatchConfig.getTaskControlQueueKey(task.getWorkstationId());
        String requestId = "TRQ-" + UUID.randomUUID();
        Map<String, String> msg = new HashMap<>();
        msg.put("type", "TASK_TERMINATE_REQUEST");
        msg.put("request_id", requestId);
        msg.put("task_id", taskNo);
        msg.put("reason", "terminated_by_user");
        msg.put("operator", StringUtils.hasText(updaterName) ? updaterName : "system");
        msg.put("requested_at", LocalDateTime.now().toString());
        try {
            redisTemplate.opsForList().rightPush(queueKey, objectMapper.writeValueAsString(msg));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize terminate command", e);
        }

        String streamKey = dispatchConfig.getLogStreamKey(task.getWorkstationId(), taskNo);
        publishTaskEvent(streamKey, "TASK_TERMINATE_ENQUEUED", taskNo,
                Map.of("queue_key", queueKey, "request_id", requestId));
        log.info("Task abort requested: taskNo={}, requestId={}", taskNo, requestId);
        return task;
    }

    public TaskResponse toResponse(LinkworkTask task) {
        TaskResponse r = new TaskResponse();
        r.setId(task.getId());
        r.setTaskNo(task.getTaskNo());
        r.setRoleId(task.getWorkstationId());
        r.setRoleName(task.getWorkstationName());
        r.setPrompt(task.getPrompt());
        r.setStatus(task.getStatus());
        r.setImage(task.getImage());
        r.setModelId(task.getSelectedModel());
        r.setAssemblyId(task.getAssemblyId());
        r.setSource(task.getSource());
        r.setCronJobId(task.getCronJobId());
        r.setCreatorId(task.getCreatorId());
        r.setCreatorName(task.getCreatorName());
        r.setTokensUsed(task.getTokensUsed());
        r.setInputTokens(task.getInputTokens());
        r.setOutputTokens(task.getOutputTokens());
        r.setRequestCount(task.getRequestCount());
        r.setTokenLimit(task.getTokenLimit());
        r.setUsagePercent(task.getUsagePercent());
        r.setDurationMs(task.getDurationMs());
        r.setCreatedAt(task.getCreatedAt());
        r.setUpdatedAt(task.getUpdatedAt());

        if (StringUtils.hasText(task.getReportJson())) {
            try {
                r.setReportJson(objectMapper.readValue(task.getReportJson(), Object.class));
            } catch (JsonProcessingException e) {
                log.warn("Failed to parse report: taskNo={}", task.getTaskNo());
            }
        }
        if (StringUtils.hasText(task.getConfigJson())) {
            try {
                r.setConfigJson(objectMapper.readValue(task.getConfigJson(), Object.class));
            } catch (JsonProcessingException e) {
                log.warn("Failed to parse config: taskNo={}", task.getTaskNo());
            }
        }

        return r;
    }

    public List<TaskResponse> toResponseList(List<LinkworkTask> tasks) {
        return tasks.stream().map(this::toResponse).collect(Collectors.toList());
    }

    private void pushToDispatchQueue(LinkworkTask task) {
        try {
            String queueKey = dispatchConfig.getTaskQueueKey(task.getWorkstationId());
            Map<String, Object> msg = new HashMap<>();
            msg.put("task_id", task.getTaskNo());
            msg.put("user_id", StringUtils.hasText(task.getCreatorId()) ? task.getCreatorId() : "system");
            msg.put("content", task.getPrompt());
            msg.put("source", task.getSource());
            msg.put("cron_job_id", task.getCronJobId());
            if (task.getWorkstationId() != null) {
                msg.put("workstation_id", String.valueOf(task.getWorkstationId()));
            }
            String json = objectMapper.writeValueAsString(msg);
            redisTemplate.opsForList().rightPush(queueKey, json);
            log.info("Task dispatched to queue: taskNo={}, queueKey={}", task.getTaskNo(), queueKey);
        } catch (JsonProcessingException e) {
            log.error("Failed to dispatch task: taskNo={}", task.getTaskNo(), e);
        }
    }

    private void publishTaskEvent(String streamKey, String eventType, String taskNo, Map<String, ?> data) {
        Map<String, String> fields = new HashMap<>();
        fields.put("event_type", eventType);
        fields.put("task_no", taskNo);
        fields.put("timestamp", LocalDateTime.now().toString());
        if (data != null && !data.isEmpty()) {
            try {
                fields.put("data", objectMapper.writeValueAsString(data));
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize event data: eventType={}", eventType);
            }
        }
        try {
            redisTemplate.opsForStream().add(streamKey, fields);
        } catch (Exception e) {
            log.warn("Failed to publish task event to stream: key={}, err={}", streamKey, e.getMessage());
        }
    }

    private void assertOwner(LinkworkTask task, String creatorId) {
        if (!StringUtils.hasText(creatorId)) throw new ForbiddenOperationException("User not authenticated");
        if (adminAccessService.isAdmin(creatorId)) return;
        if (!creatorId.equals(task.getCreatorId())) throw new ForbiddenOperationException("No permission to access this task");
    }

    private String normalizeSource(String source) {
        if (!StringUtils.hasText(source)) return "MANUAL";
        String s = source.trim().toUpperCase();
        if (!"MANUAL".equals(s) && !"CRON".equals(s) && !"API".equals(s)) {
            throw new IllegalArgumentException("Invalid task source: " + source);
        }
        return s;
    }
}
