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
import com.linkwork.mapper.TaskGitAuthMapper;
import com.linkwork.model.dto.TaskCompleteRequest;
import com.linkwork.model.dto.TaskCreateRequest;
import com.linkwork.model.dto.TaskGitTokenResponse;
import com.linkwork.model.dto.TaskResponse;
import com.linkwork.model.entity.LinkworkFile;
import com.linkwork.model.entity.LinkworkTask;
import com.linkwork.model.entity.LinkworkTaskGitAuth;
import com.linkwork.model.entity.WorkstationEntity;
import com.linkwork.model.enums.TaskStatus;
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

    private static final String RUNTIME_SIDECAR = "SIDECAR";
    private static final String RUNTIME_ALONE = "ALONE";
    private static final String DELIVERY_MODE_GIT = "git";
    private static final String DELIVERY_MODE_OSS = "oss";

    private final LinkworkTaskMapper taskMapper;
    private final LinkworkFileMapper fileMapper;
    private final TaskGitAuthMapper taskGitAuthMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final WorkstationV1Service workstationService;
    private final SnowflakeIdGenerator idGenerator;
    private final DispatchConfig dispatchConfig;
    private final CronJobV1Service cronJobService;
    private final AdminAccessService adminAccessService;

    @Transactional
    public LinkworkTask createTask(TaskCreateRequest request, String creatorId, String creatorName,
                                    String creatorIp, String source, Long cronJobId) {
        String taskNo = idGenerator.nextTaskNo();

        WorkstationEntity ws = workstationService.getById(request.getRoleId());
        if (ws == null) throw new IllegalArgumentException("Role not found: " + request.getRoleId());

        LinkworkTask task = new LinkworkTask();
        task.setTaskNo(taskNo);
        task.setWorkstationId(request.getRoleId());
        task.setWorkstationName(ws.getName());
        task.setPrompt(request.getPrompt());
        task.setStatus(TaskStatus.PENDING);
        task.setSource(normalizeSource(source));
        task.setCronJobId("CRON".equals(task.getSource()) ? cronJobId : null);
        task.setImage(ws.getImage() != null ? ws.getImage() : "ubuntu-22.04-python3.10");
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
        configMap.put("image", task.getImage());
        if (StringUtils.hasText(ws.getPrompt())) {
            String rolePrompt = ws.getPrompt().trim();
            configMap.put("rolePrompt", rolePrompt);
            configMap.put("systemPromptAppend", rolePrompt);
            Map<String, String> promptLayers = new LinkedHashMap<>();
            promptLayers.put("rolePrompt", rolePrompt);
            configMap.put("promptLayers", promptLayers);
        }
        TaskRuntimeProfile roleRuntime = resolveRoleRuntimeProfile(ws);
        configMap.put("runtimeMode", roleRuntime.runtimeMode());
        configMap.put("zzMode", roleRuntime.zzMode());
        configMap.put("runnerImage", roleRuntime.runnerImage());
        if (request.getFileIds() != null && !request.getFileIds().isEmpty()) {
            configMap.put("fileIds", request.getFileIds());
        }
        WorkstationEntity.WorkstationConfig wsc = ws.getConfigJson();
        if (wsc != null) {
            configMap.put("mcp", wsc.getMcp());
            configMap.put("skills", wsc.getSkills());
            configMap.put("knowledge", wsc.getKnowledge());
            configMap.put("gitRepos", wsc.getGitRepos());
            configMap.put("env", wsc.getEnv());
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
                taskNo, ws.getId(), ws.getName(), request.getModelId());
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
        return updateStatusWithUsage(taskNo, status, null, null);
    }

    @Transactional
    public LinkworkTask updateStatusWithUsage(String taskNo, TaskStatus status, Integer tokensUsed, Long durationMs) {
        LinkworkTask task = getTaskByNo(taskNo);
        task.setStatus(status);

        if (tokensUsed != null && tokensUsed >= 0) {
            Integer currentTokens = task.getTokensUsed();
            if (tokensUsed > 0 || currentTokens == null || currentTokens <= 0) {
                task.setTokensUsed(tokensUsed);
            }
        }

        if (durationMs != null && durationMs >= 0) {
            Long currentDuration = task.getDurationMs();
            if (durationMs > 0 || currentDuration == null || currentDuration <= 0) {
                task.setDurationMs(durationMs);
            }
        }

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
        r.setSelectedModel(task.getSelectedModel());
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

        Map<String, Object> configMap = null;
        if (StringUtils.hasText(task.getReportJson())) {
            try {
                r.setReportJson(objectMapper.readValue(task.getReportJson(), Object.class));
            } catch (JsonProcessingException e) {
                log.warn("Failed to parse report: taskNo={}", task.getTaskNo());
            }
        }
        if (StringUtils.hasText(task.getConfigJson())) {
            try {
                Object parsed = objectMapper.readValue(task.getConfigJson(), Object.class);
                r.setConfigJson(parsed);
                if (parsed instanceof Map<?, ?> rawMap) {
                    configMap = new HashMap<>();
                    for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                        configMap.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
            } catch (JsonProcessingException e) {
                log.warn("Failed to parse config: taskNo={}", task.getTaskNo());
            }
        }
        TaskRuntimeProfile runtimeProfile = resolveTaskRuntimeProfile(task, configMap);
        r.setRuntimeMode(runtimeProfile.runtimeMode());
        r.setZzMode(runtimeProfile.zzMode());
        r.setRunnerImage(runtimeProfile.runnerImage());

        return r;
    }

    public TaskResponse toShareResponse(LinkworkTask task) {
        TaskResponse response = toResponse(task);
        response.setConfigJson(null);
        return response;
    }

    public TaskGitTokenResponse getGitToken(String taskNo) {
        LinkworkTask task = getTaskByNo(taskNo);
        LambdaQueryWrapper<LinkworkTaskGitAuth> w = new LambdaQueryWrapper<>();
        w.eq(LinkworkTaskGitAuth::getTaskId, taskNo);
        LinkworkTaskGitAuth binding = taskGitAuthMapper.selectOne(w);
        if (binding == null) {
            throw new IllegalArgumentException("No git auth binding for task: " + taskNo);
        }
        TaskGitTokenResponse response = new TaskGitTokenResponse();
        response.setProvider(binding.getProvider());
        response.setToken(null);
        response.setUsername(task.getCreatorName());
        return response;
    }

    public List<TaskResponse> toResponseList(List<LinkworkTask> tasks) {
        return tasks.stream().map(this::toResponse).collect(Collectors.toList());
    }

    private void pushToDispatchQueue(LinkworkTask task) {
        try {
            String queueKey = dispatchConfig.getTaskQueueKey(task.getWorkstationId());
            Map<String, Object> taskConfig = parseTaskConfig(task.getConfigJson());
            List<Map<String, String>> gitConfig = buildDispatchGitConfig(task, taskConfig);
            String deliveryMode = resolveDispatchDeliveryMode(taskConfig, gitConfig);
            if (DELIVERY_MODE_GIT.equals(deliveryMode) && gitConfig.isEmpty()) {
                log.warn("Task dispatch downgraded to oss because git_config is empty: taskNo={}", task.getTaskNo());
                deliveryMode = DELIVERY_MODE_OSS;
            }

            Map<String, Object> msg = new HashMap<>();
            msg.put("task_id", task.getTaskNo());
            msg.put("user_id", StringUtils.hasText(task.getCreatorId()) ? task.getCreatorId() : "system");
            msg.put("content", resolveDispatchContent(task, taskConfig));
            msg.put("system_prompt_append", resolveDispatchSystemPromptAppend(task, taskConfig));
            Map<String, String> promptLayers = resolveDispatchPromptLayers(taskConfig);
            if (!promptLayers.isEmpty()) {
                msg.put("prompt_layers", promptLayers);
            }
            msg.put("delivery_mode", deliveryMode);
            if (DELIVERY_MODE_GIT.equals(deliveryMode)) {
                msg.put("git_config", gitConfig);
            }
            List<Map<String, String>> filePathMappings = parseDispatchFilePathMappings(taskConfig);
            if (!filePathMappings.isEmpty()) {
                msg.put("file_path_mappings", filePathMappings);
            }
            msg.put("source", task.getSource());
            msg.put("cron_job_id", task.getCronJobId());
            if (task.getWorkstationId() != null) {
                msg.put("workstation_id", String.valueOf(task.getWorkstationId()));
                msg.put("role_id", String.valueOf(task.getWorkstationId()));
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

    private TaskRuntimeProfile resolveTaskRuntimeProfile(LinkworkTask task, Map<String, Object> configMap) {
        String runtimeMode = normalizeRuntimeMode(readText(configMap, "runtimeMode"));
        if (!StringUtils.hasText(runtimeMode)) {
            runtimeMode = normalizeRuntimeMode(readText(configMap, "podMode"));
        }
        String runnerImage = firstNonBlank(
                readText(configMap, "runnerImage"),
                readText(configMap, "runnerBaseImage"));
        String zzMode = normalizeZzMode(readText(configMap, "zzMode"));

        TaskRuntimeProfile roleRuntime = null;
        if (!StringUtils.hasText(runtimeMode) || (RUNTIME_SIDECAR.equals(runtimeMode) && !StringUtils.hasText(runnerImage))) {
            WorkstationEntity ws = task.getWorkstationId() == null ? null : workstationService.getById(task.getWorkstationId());
            if (ws != null) {
                roleRuntime = resolveRoleRuntimeProfile(ws);
            }
        }
        if (!StringUtils.hasText(runtimeMode)) {
            runtimeMode = roleRuntime != null ? roleRuntime.runtimeMode() : RUNTIME_ALONE;
        }
        if (RUNTIME_SIDECAR.equals(runtimeMode) && !StringUtils.hasText(runnerImage)) {
            runnerImage = roleRuntime != null ? roleRuntime.runnerImage() : null;
            if (!StringUtils.hasText(runnerImage)) {
                runnerImage = task.getImage();
            }
        }
        if (!RUNTIME_SIDECAR.equals(runtimeMode)) {
            runnerImage = null;
        }
        String expectedZzMode = RUNTIME_SIDECAR.equals(runtimeMode) ? "ssh" : "local";
        if (!expectedZzMode.equalsIgnoreCase(zzMode)) {
            zzMode = expectedZzMode;
        }
        return new TaskRuntimeProfile(runtimeMode, zzMode, runnerImage);
    }

    private TaskRuntimeProfile resolveRoleRuntimeProfile(WorkstationEntity ws) {
        WorkstationEntity.WorkstationConfig config = ws.getConfigJson();
        String runtimeMode = normalizeRuntimeMode(config != null ? config.getRuntimeMode() : null);
        if (!StringUtils.hasText(runtimeMode)) {
            runtimeMode = RUNTIME_ALONE;
        }
        String runnerImage = config != null ? config.getRunnerImage() : null;
        if (!RUNTIME_SIDECAR.equals(runtimeMode)) {
            runnerImage = null;
        }
        String zzMode = RUNTIME_SIDECAR.equals(runtimeMode) ? "ssh" : "local";
        return new TaskRuntimeProfile(runtimeMode, zzMode, runnerImage);
    }

    private String readText(Map<String, Object> map, String key) {
        if (map == null || !StringUtils.hasText(key)) {
            return null;
        }
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return StringUtils.hasText(text) ? text : null;
    }

    private String normalizeRuntimeMode(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (RUNTIME_SIDECAR.equals(value) || RUNTIME_ALONE.equals(value)) {
            return value;
        }
        return null;
    }

    private String normalizeZzMode(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if ("ssh".equals(value) || "local".equals(value)) {
            return value;
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseTaskConfig(String rawConfig) {
        if (!StringUtils.hasText(rawConfig)) {
            return new HashMap<>();
        }
        try {
            Object parsed = objectMapper.readValue(rawConfig, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                Map<String, Object> result = new HashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    result.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                return result;
            }
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse task config for dispatch: {}", e.getMessage());
        }
        return new HashMap<>();
    }

    private String resolveDispatchContent(LinkworkTask task, Map<String, Object> configMap) {
        String resolved = readText(configMap, "resolvedContent");
        if (StringUtils.hasText(resolved)) {
            return resolved;
        }
        return task.getPrompt();
    }

    private String resolveDispatchSystemPromptAppend(LinkworkTask task, Map<String, Object> configMap) {
        String prompt = firstNonBlank(
                readText(configMap, "systemPromptAppend"),
                readText(configMap, "system_prompt_append"),
                readText(configMap, "rolePrompt"),
                readText(configMap, "role_prompt"));
        if (StringUtils.hasText(prompt)) {
            return prompt;
        }

        if (task.getWorkstationId() != null) {
            WorkstationEntity ws = workstationService.getById(task.getWorkstationId());
            if (ws != null && StringUtils.hasText(ws.getPrompt())) {
                return ws.getPrompt().trim();
            }
        }
        return "You are a LinkWork execution agent.";
    }

    private Map<String, String> resolveDispatchPromptLayers(Map<String, Object> configMap) {
        Map<String, Object> promptLayersNode = asMap(configMap.get("promptLayers"));
        if (promptLayersNode.isEmpty()) {
            promptLayersNode = asMap(configMap.get("prompt_layers"));
        }
        Map<String, String> layers = new LinkedHashMap<>();
        String platformPrompt = firstNonBlank(
                readText(promptLayersNode, "platformPrompt"),
                readText(promptLayersNode, "platform_prompt"));
        String rolePrompt = firstNonBlank(
                readText(promptLayersNode, "rolePrompt"),
                readText(promptLayersNode, "role_prompt"),
                readText(configMap, "rolePrompt"),
                readText(configMap, "role_prompt"));
        String userSoul = firstNonBlank(
                readText(promptLayersNode, "userSoul"),
                readText(promptLayersNode, "user_soul"));
        if (StringUtils.hasText(platformPrompt)) {
            layers.put("platform_prompt", platformPrompt);
        }
        if (StringUtils.hasText(rolePrompt)) {
            layers.put("role_prompt", rolePrompt);
        }
        if (StringUtils.hasText(userSoul)) {
            layers.put("user_soul", userSoul);
        }
        return layers;
    }

    private List<Map<String, String>> buildDispatchGitConfig(LinkworkTask task, Map<String, Object> configMap) {
        List<Map<String, String>> gitConfig = convertGitReposToDispatch(task, configMap.get("gitRepos"));
        if (!gitConfig.isEmpty()) {
            return gitConfig;
        }
        Object customRaw = configMap.get("custom");
        if (!(customRaw instanceof Map<?, ?> customMap)) {
            return List.of();
        }
        Map<String, Object> custom = asMap(customMap);
        gitConfig = convertGitReposToDispatch(task, custom.get("gitRepos"));
        if (!gitConfig.isEmpty()) {
            return gitConfig;
        }
        return convertGitReposToDispatch(task, custom.get("git_config"));
    }

    private String resolveDispatchDeliveryMode(Map<String, Object> configMap, List<Map<String, String>> gitConfig) {
        String snapshotMode = firstNonBlank(
                readText(configMap, "deliveryMode"),
                readText(configMap, "delivery_mode"));
        if (StringUtils.hasText(snapshotMode)) {
            String normalized = normalizeDeliveryMode(snapshotMode);
            if (StringUtils.hasText(normalized)) {
                return normalized;
            }
        }
        return gitConfig.isEmpty() ? DELIVERY_MODE_OSS : DELIVERY_MODE_GIT;
    }

    private String normalizeDeliveryMode(String mode) {
        if (!StringUtils.hasText(mode)) {
            return null;
        }
        String normalized = mode.trim().toLowerCase(Locale.ROOT);
        if (DELIVERY_MODE_GIT.equals(normalized) || DELIVERY_MODE_OSS.equals(normalized)) {
            return normalized;
        }
        return null;
    }

    private List<Map<String, String>> parseDispatchFilePathMappings(Map<String, Object> configMap) {
        Map<String, Object> aliasMap = asMap(configMap.get("aliasMap"));
        if (aliasMap.isEmpty()) {
            aliasMap = asMap(configMap.get("alias_map"));
        }
        if (aliasMap.isEmpty()) {
            return List.of();
        }
        List<Map<String, String>> mappings = new ArrayList<>();
        for (Map.Entry<String, Object> entry : aliasMap.entrySet()) {
            String runtimePath = textOf(entry.getKey());
            String realPath = textOf(entry.getValue());
            if (!StringUtils.hasText(runtimePath) || !StringUtils.hasText(realPath)) {
                continue;
            }
            mappings.add(Map.of("runtime_path", runtimePath, "real_path", realPath));
        }
        return mappings;
    }

    private List<Map<String, String>> convertGitReposToDispatch(LinkworkTask task, Object rawGitRepos) {
        if (!(rawGitRepos instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Map<String, String>> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            String repo = firstNonBlank(
                    textOf(map.get("repo")),
                    textOf(map.get("url")));
            String originBranch = firstNonBlank(
                    textOf(map.get("origin_branch")),
                    textOf(map.get("originBranch")),
                    textOf(map.get("branch")));
            if (!StringUtils.hasText(repo) || !StringUtils.hasText(originBranch)) {
                continue;
            }

            Map<String, String> normalized = new HashMap<>();
            normalized.put("repo", repo.trim());
            normalized.put("origin_branch", originBranch.trim());

            String taskBranch = firstNonBlank(
                    textOf(map.get("task_branch")),
                    textOf(map.get("taskBranch")));
            normalized.put("task_branch", StringUtils.hasText(taskBranch)
                    ? taskBranch.trim()
                    : "feat/" + task.getTaskNo());
            result.add(normalized);
        }
        return result;
    }

    private Map<String, Object> asMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return new HashMap<>();
        }
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private String textOf(Object raw) {
        if (raw == null) {
            return null;
        }
        String text = String.valueOf(raw).trim();
        return StringUtils.hasText(text) ? text : null;
    }

    private record TaskRuntimeProfile(String runtimeMode, String zzMode, String runnerImage) {}
}
