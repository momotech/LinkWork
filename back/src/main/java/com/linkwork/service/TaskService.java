package com.linkwork.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkwork.common.ForbiddenOperationException;
import com.linkwork.common.SnowflakeIdGenerator;
import com.linkwork.config.DispatchConfig;
import com.linkwork.mapper.WorkspaceFileMapper;
import com.linkwork.mapper.TaskMapper;
import com.linkwork.model.dto.ScaleResult;
import com.linkwork.model.dto.TaskCompleteRequest;
import com.linkwork.model.dto.TaskCreateRequest;
import com.linkwork.model.dto.TaskResponse;
import com.linkwork.model.entity.McpServerEntity;
import com.linkwork.model.entity.WorkspaceFile;
import com.linkwork.model.entity.RoleEntity;
import com.linkwork.model.entity.SkillEntity;
import com.linkwork.model.entity.Task;
import com.linkwork.model.enums.TaskOutputType;
import com.linkwork.model.enums.TaskStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 任务服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {
    private static final String DELIVERY_MODE_GIT = "git";
    private static final String DELIVERY_MODE_OSS = "oss";
    private static final String OUTPUT_CODE_GIT_BRANCH = TaskOutputType.GIT_BRANCH.getCode();
    private static final String OUTPUT_CODE_PULL_REQUEST = TaskOutputType.PULL_REQUEST.getCode();

    private final TaskMapper taskMapper;
    private final WorkspaceFileMapper workspaceFileMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RoleService roleService;
    private final RuntimeModeService runtimeModeService;
    private final SnowflakeIdGenerator idGenerator;
    private final DispatchConfig dispatchConfig;
    private final TaskOutputEstimatorAgent taskOutputEstimatorAgent;
    private final TaskGitAuthService taskGitAuthService;
    private final CronJobService cronJobService;
    private final UserSoulService userSoulService;
    private final TaskBillingUsageService taskBillingUsageService;
    private final AdminAccessService adminAccessService;
    @Autowired
    private ServiceScaleService serviceScaleService;
    @Autowired
    private McpServerService mcpServerService;

    @Autowired
    private SkillService skillService;

    @Value("${robot.prompt.platform:遵循平台工程规范：禁止吞异常；优先给结论和可执行步骤；涉及风险时明确说明并给出回滚方案。}")
    private String platformPrompt = "遵循平台工程规范：禁止吞异常；优先给结论和可执行步骤；涉及风险时明确说明并给出回滚方案。";

    @Value("${robot.prompt.user-soul-default:默认用户偏好：请使用中文沟通；先给结论，再给可执行步骤；信息不足时明确说明假设与风险。}")
    private String defaultUserSoulPrompt = "默认用户偏好：请使用中文沟通；先给结论，再给可执行步骤；信息不足时明确说明假设与风险。";

    /**
     * 创建任务
     */
    @Transactional
    public Task createTask(TaskCreateRequest request, String creatorId, String creatorName) {
        return createTask(request, creatorId, creatorName, null, true, "MANUAL", null);
    }

    /**
     * 创建任务（带创建触发 IP）
     */
    @Transactional
    public Task createTask(TaskCreateRequest request, String creatorId, String creatorName, String creatorIp) {
        return createTask(request, creatorId, creatorName, creatorIp, true, "MANUAL", null);
    }

    /**
     * 创建任务
     * 
     * 简化版本：前端只传 prompt, roleId, modelId, fileIds
     * 其他配置（image, mcp, skills 等）由后端根据岗位自动填充
     *
     * @param creatorIp 任务创建执行触发来源 IP
     * @param sendNotify 是否发送创建通知（当前实现忽略）
     */
    @Transactional
    public Task createTask(TaskCreateRequest request, String creatorId, String creatorName, String creatorIp, boolean sendNotify) {
        return createTask(request, creatorId, creatorName, creatorIp, sendNotify, "MANUAL", null);
    }

    @Transactional
    public Task createTask(TaskCreateRequest request, String creatorId, String creatorName, String creatorIp,
                           boolean sendNotify, String source, Long cronJobId) {
        // 使用雪花算法生成分布式唯一任务编号
        String taskNo = idGenerator.nextTaskNo();

        // 查询岗位信息（岗位包含默认镜像、MCP、技能等配置）
        RoleEntity role = roleService.getById(request.getRoleId());
        if (role == null) {
            throw new IllegalArgumentException("指定的岗位不存在: roleId=" + request.getRoleId());
        }
        PromptLayers promptLayers = buildPromptLayers(role, creatorId);
        String systemPromptAppend = toSystemPromptAppend(promptLayers);

        List<WorkspaceFile> selectedFiles = loadSelectedFiles(request.getFileIds(), creatorId);
        List<TaskInputFileRef> taskInputFiles = buildTaskInputFiles(selectedFiles);
        String resolvedContent = buildResolvedTaskContent(request.getPrompt(), taskInputFiles);

        // 构建任务实体
        Task task = new Task();
        task.setTaskNo(taskNo);
        task.setRoleId(request.getRoleId());
        task.setRoleName(role.getName());
        task.setPrompt(request.getPrompt());
        task.setStatus(TaskStatus.PENDING);
        task.setSource(normalizeTaskSource(source));
        task.setCronJobId("CRON".equals(task.getSource()) ? cronJobId : null);
        // 从岗位获取默认镜像，如果岗位没配置则使用默认值
        task.setImage(role.getImage() != null ? role.getImage() : "ubuntu-22.04-python3.10");
        task.setSelectedModel(request.getModelId());
        task.setCreatorId(creatorId);
        task.setCreatorName(creatorName);
        task.setCreatorIp(creatorIp);
        task.setTokensUsed(0);
        task.setDurationMs(0L);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        task.setIsDeleted(0);

        // 任务运行模式快照（严格双态：SIDECAR / ALONE）
        RuntimeModeService.RuntimeSnapshot runtimeSnapshot = runtimeModeService.resolveForRole(role);

        // 关键：先落库任务核心字段，避免网关按 taskId 反查模型时出现“任务不存在”
        taskMapper.insert(task);

        // 执行前产出预估：优先 LLM Gateway，失败兜底规则
        TaskOutputEstimatorAgent.EstimateResult estimateResult =
                taskOutputEstimatorAgent.estimateWithBranch(taskNo, request, role);
        List<String> estimatedOutput = estimateResult.estimatedOutput();
        String branchName = estimateResult.branchName();
        String deliveryMode = resolveDeliveryMode(estimatedOutput, hasValidRoleGitRepo(role), taskNo);

        // 序列化配置（包含 fileIds、岗位继承配置和预估产出）
        Map<String, Object> configMap = new HashMap<>();
        configMap.put("modelId", request.getModelId());
        configMap.put("fileIds", selectedFiles.stream().map(WorkspaceFile::getFileId).toList());
        configMap.put("systemPromptAppend", systemPromptAppend);
        configMap.put("promptLayers", toPromptLayerMap(promptLayers));
        if (!selectedFiles.isEmpty()) {
            List<Map<String, Object>> fileDetails = new ArrayList<>();
            Map<String, TaskInputFileRef> inputFileRefMap = new HashMap<>();
            Map<String, String> aliasMap = new LinkedHashMap<>();
            for (TaskInputFileRef inputFile : taskInputFiles) {
                inputFileRefMap.put(inputFile.fileId(), inputFile);
                aliasMap.put(inputFile.runtimePath(), inputFile.realPath());
            }
            for (WorkspaceFile rf : selectedFiles) {
                TaskInputFileRef ref = inputFileRefMap.get(rf.getFileId());
                fileDetails.add(Map.of(
                        "fileId", rf.getFileId(),
                        "fileName", rf.getFileName(),
                        "fileType", rf.getFileType(),
                        "ossPath", rf.getOssPath(),
                        "spaceType", rf.getSpaceType(),
                        "workstationId", rf.getWorkstationId() == null ? "" : rf.getWorkstationId(),
                        "taskInputObject", "",
                        "taskInputPath", ref != null ? ref.runtimePath() : ""));
            }
            configMap.put("files", fileDetails);
            configMap.put("resolvedContent", resolvedContent);
            configMap.put("aliasMap", aliasMap);
        }
        configMap.put("image", task.getImage());
        configMap.put("runtimeMode", runtimeSnapshot.getRuntimeMode());
        configMap.put("zzMode", runtimeSnapshot.getZzMode());
        configMap.put("runnerImage", runtimeSnapshot.getRunnerImage());
        configMap.put("estimatedOutput", estimatedOutput);
        configMap.put("deliveryMode", deliveryMode);
        if (StringUtils.hasText(branchName)) {
            configMap.put("branchName", branchName);
        }
        // 从岗位 configJson 继承 MCP、技能等配置
        if (role.getConfigJson() != null) {
            configMap.put("mcp", role.getConfigJson().getMcp());
            configMap.put("skills", role.getConfigJson().getSkills());
            configMap.put("knowledge", role.getConfigJson().getKnowledge());
            configMap.put("gitRepos", role.getConfigJson().getGitRepos());
            configMap.put("env", role.getConfigJson().getEnv());
        }
        try {
            task.setConfigJson(objectMapper.writeValueAsString(configMap));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化任务配置失败: taskNo=" + taskNo, e);
        }

        // 回写完整任务配置（含预估产物、执行模式等）
        int updated = taskMapper.updateById(task);
        if (updated != 1) {
            throw new IllegalStateException("更新任务配置失败: taskNo=" + taskNo);
        }

        // 绑定任务与 Git 认证映射（用户未授权时跳过）
        taskGitAuthService.bindTaskWithLatestGitAuth(taskNo, creatorId);

        // 创建 Redis Stream (用于 WebSocket 事件)
        // 动态格式: logs:{workstationId}:{taskNo}
        String streamKey = dispatchConfig.getLogStreamKey(task.getRoleId(), taskNo);
        publishTaskEvent(streamKey, "TASK_CREATED", taskNo, Map.of("message", "任务已创建"));

        Map<String, Object> estimateData = new HashMap<>();
        estimateData.put("estimated_output", estimatedOutput);
        estimateData.put("domains", resolveOutputDomains(estimatedOutput));
        estimateData.put("delivery_mode", deliveryMode);
        if (StringUtils.hasText(branchName)) {
            estimateData.put("branch_name", branchName);
        }
        estimateData.put("message", "已完成任务产物预估");
        publishTaskEvent(streamKey, "TASK_OUTPUT_ESTIMATED", taskNo, estimateData);

        // 关键：事务提交后再入队，避免消费者查不到任务
        final Task dispatchTask = task;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                pushToDispatchQueue(dispatchTask);
            }
        });

        log.info("任务创建成功: taskNo={}, roleId={}, roleName={}, modelId={}, deliveryMode={}, estimatedOutput={}, branchName={}, estimateSource={}",
                taskNo, task.getRoleId(), role.getName(), request.getModelId(), deliveryMode, estimatedOutput, branchName, estimateResult.source());

        // 预留 sendNotify 参数，当前不发送外部 IM 通知

        return task;
    }

    private String normalizeTaskSource(String source) {
        if (!StringUtils.hasText(source)) {
            return "MANUAL";
        }
        String normalized = source.trim().toUpperCase(Locale.ROOT);
        if (!"MANUAL".equals(normalized) && !"CRON".equals(normalized)) {
            throw new IllegalArgumentException("非法任务来源: source=" + source);
        }
        return normalized;
    }

    /**
     * 将任务推入调度队列（统一消息契约供 worker 消费）
     */
    private void pushToDispatchQueue(Task task) {
        try {
            String queueKey = dispatchConfig.getTaskQueueKey(task.getRoleId());

            Map<String, Object> message = new HashMap<>();
            message.put("task_id", task.getTaskNo());

            String userId = task.getCreatorId();
            if (!StringUtils.hasText(userId)) {
                userId = "system";
                log.warn("任务创建人为空，使用兜底 user_id=system 下发: taskNo={}", task.getTaskNo());
            }
            message.put("user_id", userId);
            message.put("content", resolveDispatchContent(task));
            message.put("system_prompt_append", resolveDispatchSystemPromptAppend(task));
            message.put("prompt_layers", resolveDispatchPromptLayers(task));
            message.put("source", task.getSource());
            message.put("cron_job_id", task.getCronJobId());
            if (task.getRoleId() != null) {
                message.put("role_id", String.valueOf(task.getRoleId()));
            }
            if (StringUtils.hasText(task.getSelectedModel())) {
                message.put("selected_model", task.getSelectedModel().trim());
            }

            List<Map<String, String>> gitConfig = buildDispatchGitConfig(task);
            String deliveryMode = resolveDispatchDeliveryMode(task, gitConfig);
            if (DELIVERY_MODE_GIT.equals(deliveryMode) && gitConfig.isEmpty()) {
                log.warn("任务下发降级为 oss：delivery_mode=git 但 git_config 为空, taskNo={}", task.getTaskNo());
                deliveryMode = DELIVERY_MODE_OSS;
            }
            message.put("delivery_mode", deliveryMode);
            if (DELIVERY_MODE_GIT.equals(deliveryMode)) {
                message.put("git_config", gitConfig);
            }
            List<Map<String, String>> filePathMappings = parseDispatchFilePathMappings(task);
            if (!filePathMappings.isEmpty()) {
                message.put("file_path_mappings", filePathMappings);
            }

            String messageJson = objectMapper.writeValueAsString(message);
            Long queueLength = redisTemplate.opsForList().rightPush(queueKey, messageJson);

            log.info("任务入队成功: taskNo={}, queueKey={}, queueLength={}",
                    task.getTaskNo(), queueKey, queueLength);

            ensurePodsForRole(task.getRoleId(), task.getTaskNo());
        } catch (JsonProcessingException e) {
            log.error("任务入队失败，序列化错误: taskNo={}", task.getTaskNo(), e);
        }
    }

    /**
     * 任务入队后检查岗位下是否有存活 Pod，无则按岗位配置扩容。
     * 异步执行，扩容失败不影响任务入队。
     */
    private void ensurePodsForRole(Long roleId, String taskNo) {
        if (roleId == null) {
            return;
        }
        String serviceId = String.valueOf(roleId);
        try {
            ScaleResult result = serviceScaleService.ensurePodsForRole(serviceId);
            if (result != null) {
                if (result.isSuccess()) {
                    log.info("任务入队触发自动扩容: taskNo={}, serviceId={}, pods={}",
                            taskNo, serviceId, result.getCurrentPodCount());
                } else {
                    log.warn("任务入队触发自动扩容失败: taskNo={}, serviceId={}, error={}",
                            taskNo, serviceId, result.getErrorMessage());
                }
            }
        } catch (Exception e) {
            log.warn("任务入队扩容检查异常（不影响任务下发）: taskNo={}, serviceId={}, error={}",
                    taskNo, serviceId, e.getMessage());
        }
    }

    /**
     * 根据任务编号获取任务详情
     */
    public Task getTaskByNo(String taskNo) {
        LambdaQueryWrapper<Task> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Task::getTaskNo, taskNo);
        Task task = taskMapper.selectOne(wrapper);
        if (task == null) {
            throw new IllegalArgumentException("任务不存在: " + taskNo);
        }
        return task;
    }

    /**
     * 根据任务编号获取任务详情（按创建人隔离）
     */
    public Task getTaskByNo(String taskNo, String creatorId) {
        Task task = getTaskByNo(taskNo);
        assertTaskOwner(task, creatorId);
        return task;
    }

    /**
     * 根据ID获取任务详情
     */
    public Task getTask(Long taskId) {
        Task task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("任务不存在: " + taskId);
        }
        return task;
    }

    /**
     * 获取任务列表
     *
     * @param roleId   按岗位 ID 筛选（可选）
     * @param status   按状态筛选（可选）
     * @param page     页码
     * @param pageSize 每页大小
     */
    public Page<Task> listTasks(Long roleId, String status, Integer page, Integer pageSize) {
        return listTasks(roleId, status, page, pageSize, null);
    }

    /**
     * 获取任务列表（按创建人隔离）
     */
    public Page<Task> listTasks(Long roleId, String status, Integer page, Integer pageSize, String creatorId) {
        LambdaQueryWrapper<Task> wrapper = new LambdaQueryWrapper<>();

        if (StringUtils.hasText(creatorId) && !isAdmin(creatorId)) {
            wrapper.eq(Task::getCreatorId, creatorId);
        }
        if (roleId != null) {
            wrapper.eq(Task::getRoleId, roleId);
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(Task::getStatus, TaskStatus.valueOf(status.toUpperCase()));
        }
        wrapper.orderByDesc(Task::getCreatedAt);

        return taskMapper.selectPage(new Page<>(page, pageSize), wrapper);
    }

    /**
     * 更新任务状态
     */
    @Transactional
    public Task updateStatus(String taskNo, TaskStatus status) {
        return updateStatusWithUsage(taskNo, status, null, null);
    }

    /**
     * 更新任务状态（可选补齐 tokens/duration，并在终态补齐计费字段）
     */
    @Transactional
    public Task updateStatusWithUsage(String taskNo, TaskStatus status, Integer tokensUsed, Long durationMs) {
        Task task = getTaskByNo(taskNo);
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

        if (isTerminalStatus(status)) {
            TaskBillingUsageService.UsageSnapshot billingSnapshot = taskBillingUsageService.fetchTaskUsage(taskNo).orElse(null);
            if (billingSnapshot != null) {
                if (billingSnapshot.tokenUsed() != null) {
                    task.setTokensUsed(billingSnapshot.tokenUsed());
                }
                task.setInputTokens(billingSnapshot.inputTokens());
                task.setOutputTokens(billingSnapshot.outputTokens());
                task.setRequestCount(billingSnapshot.requestCount());
                task.setTokenLimit(billingSnapshot.tokenLimit());
                task.setUsagePercent(billingSnapshot.usagePercent());
            }
        }

        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        cronJobService.onTaskStatusChanged(task, status);
        log.info("任务状态更新: taskNo={}, status={}, tokensUsed={}, inputTokens={}, outputTokens={}, requestCount={}, durationMs={}",
                taskNo, status, task.getTokensUsed(), task.getInputTokens(), task.getOutputTokens(), task.getRequestCount(), task.getDurationMs());
        return task;
    }

    private boolean isTerminalStatus(TaskStatus status) {
        return status == TaskStatus.COMPLETED || status == TaskStatus.FAILED || status == TaskStatus.ABORTED;
    }

    /**
     * 任务完成回写
     * 
     * Worker 执行完毕后调用，回写 tokens_used, duration_ms, report_json, status
     */
    @Transactional
    public Task completeTask(String taskNo, TaskCompleteRequest request) {
        Task task = getTaskByNo(taskNo);

        // 校验状态：只有 RUNNING 或 PENDING 的任务可以回写完成
        if (task.getStatus() != TaskStatus.RUNNING && task.getStatus() != TaskStatus.PENDING) {
            throw new IllegalArgumentException("任务当前状态不允许回写完成: " + task.getStatus());
        }

        // 解析目标状态
        TaskStatus targetStatus;
        try {
            targetStatus = TaskStatus.valueOf(request.getStatus().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("无效的任务状态: " + request.getStatus() + "，允许值: COMPLETED, FAILED");
        }
        if (targetStatus != TaskStatus.COMPLETED && targetStatus != TaskStatus.FAILED) {
            throw new IllegalArgumentException("回写状态只能为 COMPLETED 或 FAILED，收到: " + request.getStatus());
        }

        int finalTokensUsed = request.getTokensUsed();
        TaskBillingUsageService.UsageSnapshot billingSnapshot = taskBillingUsageService.fetchTaskUsage(taskNo).orElse(null);
        UsageFallback streamUsage = resolveUsageFromStream(task);
        if (billingSnapshot != null && billingSnapshot.tokenUsed() != null) {
            finalTokensUsed = billingSnapshot.tokenUsed();
        } else if (finalTokensUsed <= 0 && streamUsage.tokensUsed() != null && streamUsage.tokensUsed() > 0) {
            finalTokensUsed = streamUsage.tokensUsed();
        }
        Long finalDurationMs = request.getDurationMs();
        if ((finalDurationMs == null || finalDurationMs <= 0)
                && streamUsage.durationMs() != null
                && streamUsage.durationMs() > 0) {
            finalDurationMs = streamUsage.durationMs();
        }
        if ((finalDurationMs == null || finalDurationMs <= 0) && task.getCreatedAt() != null) {
            finalDurationMs = Math.max(0, Duration.between(task.getCreatedAt(), LocalDateTime.now()).toMillis());
        }

        // 更新字段（优先使用计费网关真实值）
        task.setStatus(targetStatus);
        task.setTokensUsed(finalTokensUsed);
        task.setInputTokens(billingSnapshot != null ? billingSnapshot.inputTokens() : null);
        task.setOutputTokens(billingSnapshot != null ? billingSnapshot.outputTokens() : null);
        task.setRequestCount(billingSnapshot != null ? billingSnapshot.requestCount() : null);
        task.setTokenLimit(billingSnapshot != null ? billingSnapshot.tokenLimit() : null);
        task.setUsagePercent(billingSnapshot != null ? billingSnapshot.usagePercent() : null);
        task.setDurationMs(finalDurationMs);
        task.setUpdatedAt(LocalDateTime.now());

        Map<String, Object> reportMap = buildTaskReport(task, request, targetStatus, finalTokensUsed);
        try {
            task.setReportJson(objectMapper.writeValueAsString(reportMap));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化任务报告失败: taskNo=" + taskNo, e);
        }

        taskMapper.updateById(task);
        cronJobService.onTaskStatusChanged(task, targetStatus);

        // 发送完成事件到 Redis Stream: logs:{workstationId}:{taskNo}
        String streamKey = dispatchConfig.getLogStreamKey(task.getRoleId(), taskNo);
        Map<String, Object> completionData = new HashMap<>();
        completionData.put("tokens_used", finalTokensUsed);
        completionData.put("input_tokens", task.getInputTokens());
        completionData.put("output_tokens", task.getOutputTokens());
        completionData.put("request_count", task.getRequestCount());
        completionData.put("duration_ms", finalDurationMs);
        publishTaskEvent(streamKey,
                targetStatus == TaskStatus.COMPLETED ? "TASK_COMPLETED" : "TASK_FAILED",
                taskNo,
                completionData);

        Map<String, Object> reportReadyData = new HashMap<>();
        reportReadyData.put("report", reportMap);
        reportReadyData.put("message", "执行报告已生成");
        publishTaskEvent(streamKey, "TASK_REPORT_READY", taskNo, reportReadyData);

        log.info("任务完成回写: taskNo={}, status={}, tokensUsed={}, inputTokens={}, outputTokens={}, requestCount={}, durationMs={}",
                taskNo, targetStatus, finalTokensUsed, task.getInputTokens(), task.getOutputTokens(), task.getRequestCount(), finalDurationMs);

        return task;
    }

    private UsageFallback resolveUsageFromStream(Task task) {
        if (task == null || !StringUtils.hasText(task.getTaskNo())) {
            return new UsageFallback(null, null);
        }

        List<String> streamKeys = List.of(
                dispatchConfig.getLogStreamKey(task.getRoleId(), task.getTaskNo()),
                "stream:task:" + task.getTaskNo() + ":events",
                "stream:task:" + task.getTaskNo()
        );

        Integer tokensUsed = null;
        Long durationMs = null;
        for (String streamKey : streamKeys) {
            try {
                List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                        .read(StreamOffset.fromStart(streamKey));
                if (records == null || records.isEmpty()) {
                    continue;
                }
                for (MapRecord<String, Object, Object> record : records) {
                    Map<String, Object> eventData = extractEventData(record);
                    Integer eventTokens = resolveIntByKeys(eventData, "tokens_used", "tokensUsed", "token_usage");
                    Long eventDuration = resolveLongByKeys(eventData, "duration_ms", "durationMs", "elapsed_ms");
                    if (eventTokens != null && eventTokens >= 0) {
                        tokensUsed = eventTokens;
                    }
                    if (eventDuration != null && eventDuration > 0) {
                        durationMs = eventDuration;
                    }
                }
            } catch (Exception e) {
                log.debug("从 Stream 提取 usage 失败: taskNo={}, streamKey={}, error={}",
                        task.getTaskNo(), streamKey, e.getMessage());
            }
        }
        return new UsageFallback(tokensUsed, durationMs);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractEventData(MapRecord<String, Object, Object> record) {
        Map<String, Object> rawEvent = new HashMap<>();
        record.getValue().forEach((k, v) -> rawEvent.put(k.toString(), v));

        Object payloadObj = rawEvent.get("payload");
        if (payloadObj instanceof String payloadStr && payloadStr.startsWith("{")) {
            try {
                Map<String, Object> payloadMap = objectMapper.readValue(payloadStr, Map.class);
                Object innerData = payloadMap.get("data");
                if (innerData instanceof String dataStr && (dataStr.startsWith("{") || dataStr.startsWith("["))) {
                    try {
                        payloadMap.put("data", objectMapper.readValue(dataStr, Object.class));
                    } catch (Exception ignored) {
                    }
                }
                Object payloadData = payloadMap.get("data");
                if (payloadData instanceof Map<?, ?> dataMap) {
                    dataMap.forEach((k, v) -> payloadMap.putIfAbsent(String.valueOf(k), v));
                }
                return payloadMap;
            } catch (Exception e) {
                log.debug("解析 payload 失败，回退扁平事件: recordId={}, error={}", record.getId(), e.getMessage());
            }
        }

        Object dataObj = rawEvent.get("data");
        if (dataObj instanceof String dataStr && (dataStr.startsWith("{") || dataStr.startsWith("["))) {
            try {
                rawEvent.put("data", objectMapper.readValue(dataStr, Object.class));
            } catch (Exception ignored) {
            }
        }
        Object flatData = rawEvent.get("data");
        if (flatData instanceof Map<?, ?> dataMap) {
            dataMap.forEach((k, v) -> rawEvent.putIfAbsent(String.valueOf(k), v));
        }

        return rawEvent;
    }

    private Integer resolveIntByKeys(Map<String, Object> eventData, String... keys) {
        Long value = resolveLongByKeys(eventData, keys);
        if (value == null || value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) {
            return null;
        }
        return value.intValue();
    }

    private Long resolveLongByKeys(Map<String, Object> eventData, String... keys) {
        if (eventData == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object raw = eventData.get(key);
            if (raw == null) {
                continue;
            }
            try {
                return Long.parseLong(String.valueOf(raw));
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private record UsageFallback(Integer tokensUsed, Long durationMs) {}

    /**
     * 终止任务
     *
     * terminate 接口只负责受理并下发终止命令，不会直接把任务写为 ABORTED。
     */
    @Transactional
    public Task abortTask(String taskNo, String updaterId, String updaterName) {
        Task task = StringUtils.hasText(updaterId) ? getTaskByNo(taskNo, updaterId) : getTaskByNo(taskNo);

        // 只有运行中或等待中的任务可以终止
        if (task.getStatus() != TaskStatus.RUNNING && task.getStatus() != TaskStatus.PENDING) {
            throw new IllegalArgumentException("任务当前状态不允许终止: " + task.getStatus());
        }

        task.setUpdaterId(updaterId);
        task.setUpdaterName(updaterName);
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);

        TerminateEnqueueResult enqueueResult = pushTerminateCommand(task, updaterName);

        // 发布终止入队事件到 Redis Stream: logs:{workstationId}:{taskNo}
        String streamKey = dispatchConfig.getLogStreamKey(task.getRoleId(), taskNo);
        Map<String, Object> enqueueData = new HashMap<>();
        enqueueData.put("queue_key", enqueueResult.queueKey());
        enqueueData.put("request_id", enqueueResult.requestId());
        enqueueData.put("message", "任务终止命令已入队");
        publishTaskEvent(streamKey, "TASK_TERMINATE_ENQUEUED", taskNo, enqueueData);

        log.info("任务终止请求已受理: taskNo={}, queueKey={}, requestId={}",
                taskNo, enqueueResult.queueKey(), enqueueResult.requestId());

        return task;
    }

    private boolean isAdmin(String userId) {
        return adminAccessService.isAdmin(userId);
    }

    private void assertTaskOwner(Task task, String creatorId) {
        if (!StringUtils.hasText(creatorId)) {
            throw new ForbiddenOperationException("用户未登录或登录态失效");
        }
        if (isAdmin(creatorId)) {
            return;
        }
        if (!StringUtils.hasText(task.getCreatorId()) || !creatorId.equals(task.getCreatorId())) {
            throw new ForbiddenOperationException("无权限访问该任务");
        }
    }

    private TerminateEnqueueResult pushTerminateCommand(Task task, String updaterName) {
        String taskNo = task.getTaskNo();
        String queueKey = dispatchConfig.getTaskControlQueueKey(task.getRoleId());
        String requestId = "TRQ-" + UUID.randomUUID();

        Map<String, String> message = new HashMap<>();
        message.put("type", "TASK_TERMINATE_REQUEST");
        message.put("request_id", requestId);
        message.put("task_id", taskNo);
        message.put("reason", "terminated_by_user");
        message.put("operator", StringUtils.hasText(updaterName) ? updaterName : "system");
        message.put("requested_at", LocalDateTime.now().toString());

        final String messageJson;
        try {
            messageJson = objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化任务终止命令失败: taskNo=" + taskNo, e);
        }

        Long queueLength = redisTemplate.opsForList().rightPush(queueKey, messageJson);
        if (queueLength == null) {
            throw new IllegalStateException("任务终止命令入队失败: taskNo=" + taskNo);
        }

        log.info("任务终止命令入队成功: taskNo={}, queueKey={}, queueLength={}, requestId={}",
                taskNo, queueKey, queueLength, requestId);
        return new TerminateEnqueueResult(queueKey, requestId);
    }


    /**
     * 转换为响应 DTO
     */
    public TaskResponse toResponse(Task task) {
        TaskResponse response = new TaskResponse();
        response.setTaskNo(task.getTaskNo());
        response.setRoleId(task.getRoleId());
        response.setRoleName(task.getRoleName());
        response.setPrompt(task.getPrompt());
        response.setStatus(task.getStatus());
        response.setSource(task.getSource());
        response.setImage(task.getImage());
        response.setSelectedModel(task.getSelectedModel());
        response.setCreator(task.getCreatorName());
        response.setCreatedAt(task.getCreatedAt() != null ? task.getCreatedAt().toString() : null);

        // 构建 Usage
        TaskResponse.Usage usage = new TaskResponse.Usage();
        usage.setTokensUsed(task.getTokensUsed());
        usage.setInputTokens(task.getInputTokens());
        usage.setOutputTokens(task.getOutputTokens());
        usage.setRequestCount(task.getRequestCount());
        usage.setTokenLimit(task.getTokenLimit());
        usage.setUsagePercent(task.getUsagePercent());
        usage.setDuration(formatDuration(task.getDurationMs()));
        response.setUsage(usage);

        // 解析报告
        if (StringUtils.hasText(task.getReportJson())) {
            try {
                response.setReport(objectMapper.readValue(task.getReportJson(), TaskResponse.Report.class));
            } catch (JsonProcessingException e) {
                log.error("解析任务报告失败", e);
            }
        }

        com.fasterxml.jackson.databind.JsonNode configNode = null;
        if (StringUtils.hasText(task.getConfigJson())) {
            try {
                configNode = objectMapper.readTree(task.getConfigJson());
            } catch (Exception e) {
                throw new IllegalStateException("解析任务配置失败: taskNo=" + task.getTaskNo(), e);
            }
        }

        // 运行模式字段强校验：仅 SIDECAR / ALONE，不允许第三态
        RuntimeModeService.RuntimeSnapshot runtimeSnapshot = resolveTaskRuntimeSnapshot(task, configNode);
        response.setRuntimeMode(runtimeSnapshot.getRuntimeMode());
        response.setZzMode(runtimeSnapshot.getZzMode());
        response.setRunnerImage(runtimeSnapshot.getRunnerImage());

        // 从 configJson 解析扩展字段 (repo/branch/mcp/skills/knowledge/env)
        if (configNode != null) {
            String branchName = null;
            String gitRepoBranch = null;

            // estimatedOutput
            var estimatedOutputNode = configNode.get("estimatedOutput");
            if (estimatedOutputNode != null && estimatedOutputNode.isArray()) {
                response.setEstimatedOutput(objectMapper.convertValue(estimatedOutputNode,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)));
            }

            var branchNameNode = configNode.get("branchName");
            if (branchNameNode != null && branchNameNode.isTextual()) {
                branchName = branchNameNode.asText();
                if (StringUtils.hasText(branchName)) {
                    branchName = branchName.trim();
                    response.setBranchName(branchName);
                }
            }
            var deliveryModeNode = configNode.get("deliveryMode");
            if (deliveryModeNode != null && deliveryModeNode.isTextual()) {
                response.setDeliveryMode(normalizeDeliveryMode(deliveryModeNode.asText(), task.getTaskNo()));
            }

            // gitRepos → repo + branch
            var gitRepos = configNode.get("gitRepos");
            if (gitRepos != null && gitRepos.isArray() && !gitRepos.isEmpty()) {
                var first = gitRepos.get(0);
                if (first.has("url")) {
                    response.setRepo(first.get("url").asText());
                }
                if (first.has("branch")) {
                    gitRepoBranch = first.get("branch").asText();
                    if (StringUtils.hasText(gitRepoBranch)) {
                        gitRepoBranch = gitRepoBranch.trim();
                    }
                }
            }

            // branch（兼容历史配置）
            var branchNode = configNode.get("branch");
            if (!StringUtils.hasText(gitRepoBranch) && branchNode != null && branchNode.isTextual()) {
                gitRepoBranch = branchNode.asText();
                if (StringUtils.hasText(gitRepoBranch)) {
                    gitRepoBranch = gitRepoBranch.trim();
                }
            }

            if (StringUtils.hasText(branchName)) {
                response.setBranch(branchName);
            } else if (StringUtils.hasText(gitRepoBranch)) {
                response.setBranch(gitRepoBranch);
            }

            // mcp
            var mcpNode = configNode.get("mcp");
            if (mcpNode != null && mcpNode.isArray()) {
                response.setMcp(resolveMcpDisplayNames(mcpNode));
            }

            // skills
            var skillsNode = configNode.get("skills");
            if (skillsNode != null && skillsNode.isArray()) {
                response.setSkills(resolveSkillDisplayNames(skillsNode));
            }

            // knowledge
            var knowledgeNode = configNode.get("knowledge");
            if (knowledgeNode != null && knowledgeNode.isArray()) {
                response.setKnowledge(objectMapper.convertValue(knowledgeNode,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)));
            }

            // env: [{key, value}] → Map<String, String>
            var envNode = configNode.get("env");
            if (envNode != null && envNode.isArray()) {
                var envMap = new LinkedHashMap<String, String>();
                for (var item : envNode) {
                    String k = item.has("key") ? item.get("key").asText() : null;
                    String v = item.has("value") ? item.get("value").asText() : "";
                    if (k != null && !k.isEmpty()) {
                        envMap.put(k, v);
                    }
                }
                if (!envMap.isEmpty()) {
                    response.setEnv(envMap);
                }
            }
        }

        if (!StringUtils.hasText(response.getDeliveryMode())) {
            response.setDeliveryMode(resolveDispatchDeliveryMode(task, buildDispatchGitConfig(task)));
        }

        return response;
    }

    /**
     * 访客分享视图：移除敏感配置字段，仅保留任务执行详情。
     */
    public TaskResponse toShareResponse(Task task) {
        TaskResponse response = toResponse(task);
        response.setEnv(null);
        response.setKnowledge(null);
        return response;
    }

    private List<String> resolveMcpDisplayNames(com.fasterxml.jackson.databind.JsonNode refsNode) {
        List<String> refs = extractConfigRefs(refsNode);
        if (refs.isEmpty()) {
            return List.of();
        }

        Map<String, String> displayByRef = new HashMap<>();
        List<Long> idRefs = new ArrayList<>();
        List<String> nameRefs = new ArrayList<>();

        for (String ref : refs) {
            try {
                idRefs.add(Long.parseLong(ref));
            } catch (NumberFormatException ex) {
                nameRefs.add(ref);
            }
        }

        if (!idRefs.isEmpty()) {
            List<McpServerEntity> entities = mcpServerService.list(
                    new LambdaQueryWrapper<McpServerEntity>().in(McpServerEntity::getId, idRefs));
            for (McpServerEntity entity : entities) {
                String displayName = StringUtils.hasText(entity.getName())
                        ? entity.getName()
                        : String.valueOf(entity.getId());
                displayByRef.put(String.valueOf(entity.getId()), displayName);
                if (StringUtils.hasText(entity.getName())) {
                    displayByRef.put(entity.getName(), displayName);
                }
            }
        }

        if (!nameRefs.isEmpty()) {
            List<McpServerEntity> entities = mcpServerService.list(
                    new LambdaQueryWrapper<McpServerEntity>().in(McpServerEntity::getName, nameRefs));
            for (McpServerEntity entity : entities) {
                String displayName = StringUtils.hasText(entity.getName())
                        ? entity.getName()
                        : String.valueOf(entity.getId());
                displayByRef.put(entity.getName(), displayName);
                displayByRef.put(String.valueOf(entity.getId()), displayName);
            }
        }

        List<String> result = new ArrayList<>();
        for (String ref : refs) {
            result.add(displayByRef.getOrDefault(ref, ref));
        }
        return result;
    }

    private List<String> resolveSkillDisplayNames(com.fasterxml.jackson.databind.JsonNode refsNode) {
        List<String> refs = extractConfigRefs(refsNode);
        if (refs.isEmpty()) {
            return List.of();
        }

        Map<String, String> displayByRef = new HashMap<>();
        List<Long> idRefs = new ArrayList<>();
        List<String> nameRefs = new ArrayList<>();

        for (String ref : refs) {
            try {
                idRefs.add(Long.parseLong(ref));
            } catch (NumberFormatException ex) {
                nameRefs.add(ref);
            }
        }

        if (!idRefs.isEmpty()) {
            List<SkillEntity> entities = skillService.list(
                    new LambdaQueryWrapper<SkillEntity>().in(SkillEntity::getId, idRefs));
            for (SkillEntity entity : entities) {
                String displayName = resolveSkillDisplayName(entity);
                displayByRef.put(String.valueOf(entity.getId()), displayName);
                if (StringUtils.hasText(entity.getName())) {
                    displayByRef.put(entity.getName(), displayName);
                }
            }
        }

        if (!nameRefs.isEmpty()) {
            List<SkillEntity> entities = skillService.list(
                    new LambdaQueryWrapper<SkillEntity>().in(SkillEntity::getName, nameRefs));
            for (SkillEntity entity : entities) {
                String displayName = resolveSkillDisplayName(entity);
                displayByRef.put(entity.getName(), displayName);
                displayByRef.put(String.valueOf(entity.getId()), displayName);
            }
        }

        List<String> result = new ArrayList<>();
        for (String ref : refs) {
            result.add(displayByRef.getOrDefault(ref, ref));
        }
        return result;
    }

    private String resolveSkillDisplayName(SkillEntity entity) {
        if (StringUtils.hasText(entity.getDisplayName())) {
            return entity.getDisplayName();
        }
        if (StringUtils.hasText(entity.getName())) {
            return entity.getName();
        }
        return String.valueOf(entity.getId());
    }

    private List<String> extractConfigRefs(com.fasterxml.jackson.databind.JsonNode refsNode) {
        if (refsNode == null || !refsNode.isArray()) {
            return List.of();
        }

        List<String> refs = new ArrayList<>();
        for (var refNode : refsNode) {
            String ref = extractConfigRef(refNode);
            if (StringUtils.hasText(ref)) {
                refs.add(ref.trim());
            }
        }
        return refs;
    }

    private String extractConfigRef(com.fasterxml.jackson.databind.JsonNode refNode) {
        if (refNode == null || refNode.isNull()) {
            return null;
        }
        if (refNode.isTextual() || refNode.isNumber()) {
            return refNode.asText();
        }
        if (!refNode.isObject()) {
            return null;
        }

        String[] candidateKeys = {"id", "value", "name", "label", "key"};
        for (String key : candidateKeys) {
            var valueNode = refNode.get(key);
            if (valueNode == null || valueNode.isNull()) {
                continue;
            }
            if (valueNode.isTextual() || valueNode.isNumber()) {
                String value = valueNode.asText();
                if (StringUtils.hasText(value)) {
                    return value;
                }
            }
        }
        return null;
    }

    /**
     * 批量转换为响应 DTO
     */
    public List<TaskResponse> toResponseList(List<Task> tasks) {
        return tasks.stream().map(this::toResponse).collect(Collectors.toList());
    }

    private List<WorkspaceFile> loadSelectedFiles(List<String> fileIds, String creatorId) {
        if (fileIds == null || fileIds.isEmpty()) {
            return Collections.emptyList();
        }

        LinkedHashSet<String> uniqueIds = new LinkedHashSet<>();
        for (String fileId : fileIds) {
            if (!StringUtils.hasText(fileId)) {
                continue;
            }
            uniqueIds.add(fileId.trim());
        }
        if (uniqueIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<WorkspaceFile> files = new ArrayList<>();
        for (String fileId : uniqueIds) {
            WorkspaceFile file = workspaceFileMapper.selectOne(
                    new LambdaQueryWrapper<WorkspaceFile>()
                            .eq(WorkspaceFile::getFileId, fileId)
                            .isNull(WorkspaceFile::getDeletedAt)
                            .last("limit 1"));
            if (file == null) {
                throw new IllegalArgumentException("文件不存在: " + fileId);
            }
            if (!creatorId.equals(file.getUserId())) {
                throw new IllegalArgumentException("无权引用该文件: " + fileId);
            }
            files.add(file);
        }
        return files;
    }

    private List<TaskInputFileRef> buildTaskInputFiles(List<WorkspaceFile> files) {
        if (files == null || files.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, Long> nameCount = files.stream()
                .collect(Collectors.groupingBy(WorkspaceFile::getFileName, Collectors.counting()));

        Map<String, Integer> nameSeq = new HashMap<>();

        List<TaskInputFileRef> refs = new ArrayList<>();
        for (WorkspaceFile file : files) {
            String displayName = file.getFileName();
            if (nameCount.getOrDefault(displayName, 0L) > 1) {
                int seq = nameSeq.merge(displayName, 1, Integer::sum);
                if (seq > 1) {
                    displayName = appendSequence(displayName, seq - 1);
                }
            }
            String readablePath = buildReadableRuntimePath(file, displayName);
            String realPath = buildRuntimeFilePath(file);
            refs.add(new TaskInputFileRef(file.getFileId(), file.getFileName(), readablePath, realPath));
        }
        return refs;
    }

    private String appendSequence(String fileName, int seq) {
        int dotIdx = fileName.lastIndexOf('.');
        if (dotIdx > 0) {
            return fileName.substring(0, dotIdx) + " (" + seq + ")" + fileName.substring(dotIdx);
        }
        return fileName + " (" + seq + ")";
    }

    private String buildReadableRuntimePath(WorkspaceFile file, String displayName) {
        return buildWorkspaceFileBase(file) + displayName;
    }

    private String buildResolvedTaskContent(String prompt, List<TaskInputFileRef> refs) {
        String basePrompt = prompt == null ? "" : prompt;
        if (refs == null || refs.isEmpty()) {
            return basePrompt;
        }

        Map<String, Long> nameCount = refs.stream().collect(
                Collectors.groupingBy(TaskInputFileRef::originalFileName, LinkedHashMap::new, Collectors.counting()));

        String resolved = basePrompt;
        List<TaskInputFileRef> sorted = refs.stream()
                .sorted(Comparator.comparingInt(
                        (TaskInputFileRef ref) -> ref.originalFileName() == null ? 0 : ref.originalFileName().length())
                        .reversed())
                .toList();

        for (TaskInputFileRef ref : sorted) {
            String originalName = ref.originalFileName();
            if (!StringUtils.hasText(originalName)) {
                continue;
            }
            if (StringUtils.hasText(ref.fileId())) {
                resolved = resolved.replace("@" + ref.fileId(), ref.runtimePath());
            }
            if (nameCount.getOrDefault(originalName, 0L) != 1L) {
                continue;
            }
            resolved = resolved.replace("@" + originalName, ref.runtimePath());
        }

        StringBuilder appendix = new StringBuilder();
        appendix.append("\n\n可用附件路径：");
        for (TaskInputFileRef ref : refs) {
            appendix.append("\n- ")
                    .append(ref.originalFileName())
                    .append(" => ")
                    .append(ref.runtimePath());
        }
        return resolved + appendix;
    }

    private String resolveDispatchContent(Task task) {
        if (task == null) {
            return "";
        }
        String resolved = parseResolvedContentFromConfig(task);
        if (StringUtils.hasText(resolved)) {
            return resolved;
        }
        return task.getPrompt();
    }

    private String resolveDispatchSystemPromptAppend(Task task) {
        if (task == null || !StringUtils.hasText(task.getConfigJson())) {
            throw new IllegalStateException("任务缺少 systemPromptAppend: taskNo=" + (task == null ? "" : task.getTaskNo()));
        }
        try {
            var configNode = objectMapper.readTree(task.getConfigJson());
            var node = configNode.get("systemPromptAppend");
            if (node != null && node.isTextual() && StringUtils.hasText(node.asText())) {
                return node.asText();
            }
        } catch (Exception e) {
            throw new IllegalStateException("解析 systemPromptAppend 失败: taskNo=" + task.getTaskNo(), e);
        }
        throw new IllegalStateException("任务缺少 systemPromptAppend: taskNo=" + task.getTaskNo());
    }

    private Map<String, String> resolveDispatchPromptLayers(Task task) {
        if (task == null || !StringUtils.hasText(task.getConfigJson())) {
            throw new IllegalStateException("任务缺少 promptLayers: taskNo=" + (task == null ? "" : task.getTaskNo()));
        }
        try {
            var configNode = objectMapper.readTree(task.getConfigJson());
            var layersNode = configNode.get("promptLayers");
            if (layersNode == null || !layersNode.isObject()) {
                throw new IllegalStateException("任务缺少 promptLayers: taskNo=" + task.getTaskNo());
            }
            String rolePrompt = readRequiredLayer(layersNode, "rolePrompt", task.getTaskNo());
            String platformPromptText = readRequiredLayer(layersNode, "platformPrompt", task.getTaskNo());
            String userSoul = readRequiredLayer(layersNode, "userSoul", task.getTaskNo());

            Map<String, String> layers = new LinkedHashMap<>();
            layers.put("platform_prompt", platformPromptText);
            layers.put("role_prompt", rolePrompt);
            layers.put("user_soul", userSoul);
            return layers;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("解析 promptLayers 失败: taskNo=" + task.getTaskNo(), e);
        }
    }

    private String readRequiredLayer(com.fasterxml.jackson.databind.JsonNode layersNode, String field, String taskNo) {
        var node = layersNode.get(field);
        if (node == null || !node.isTextual() || !StringUtils.hasText(node.asText())) {
            throw new IllegalStateException("任务 promptLayer 缺失: taskNo=" + taskNo + ", field=" + field);
        }
        return node.asText();
    }

    private PromptLayers buildPromptLayers(RoleEntity role, String creatorId) {
        String rolePrompt = normalizePromptLayer(role == null ? null : role.getPrompt());
        if (!StringUtils.hasText(rolePrompt)) {
            Long roleId = role == null ? null : role.getId();
            throw new IllegalArgumentException("ROLE_PROMPT_INVALID: 岗位 Prompt 不能为空, roleId=" + roleId);
        }

        String platformPromptText = normalizePromptLayer(platformPrompt);
        if (!StringUtils.hasText(platformPromptText)) {
            throw new IllegalArgumentException("PLATFORM_PROMPT_INVALID: 平台 Prompt 不能为空");
        }

        String userSoul = normalizePromptLayer(userSoulService.getOptionalSoulContent(creatorId));
        if (!StringUtils.hasText(userSoul)) {
            userSoul = normalizePromptLayer(defaultUserSoulPrompt);
            log.info("用户 Soul 缺失，使用默认用户层 Prompt: userId={}", creatorId);
        }
        if (!StringUtils.hasText(userSoul)) {
            throw new IllegalArgumentException("SOUL_CONTENT_INVALID: Soul 内容不能为空");
        }

        return new PromptLayers(rolePrompt, platformPromptText, userSoul);
    }

    private String toSystemPromptAppend(PromptLayers promptLayers) {
        return "<!-- 三层 Prompt 合并结果：平台层优先，其次岗位层，最后用户层 -->\n"
                + "<prompt_layers>\n"
                + "  <!-- 平台层：全局工程规范与安全约束 -->\n"
                + "  <platform_prompt>\n"
                + promptLayers.platformPrompt()
                + "\n  </platform_prompt>\n\n"
                + "  <!-- 岗位层：角色职责与领域能力 -->\n"
                + "  <role_prompt>\n"
                + promptLayers.rolePrompt()
                + "\n  </role_prompt>\n\n"
                + "  <!-- 用户层：个人偏好与协作风格 -->\n"
                + "  <user_soul>\n"
                + promptLayers.userSoul()
                + "\n  </user_soul>\n"
                + "</prompt_layers>";
    }

    private Map<String, String> toPromptLayerMap(PromptLayers promptLayers) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("platformPrompt", promptLayers.platformPrompt());
        map.put("rolePrompt", promptLayers.rolePrompt());
        map.put("userSoul", promptLayers.userSoul());
        return map;
    }

    private String normalizePromptLayer(String text) {
        return text == null ? "" : text.trim();
    }

    private String parseResolvedContentFromConfig(Task task) {
        if (task == null || !StringUtils.hasText(task.getConfigJson())) {
            return "";
        }
        try {
            var configNode = objectMapper.readTree(task.getConfigJson());
            var resolvedContentNode = configNode.get("resolvedContent");
            if (resolvedContentNode != null && resolvedContentNode.isTextual()) {
                return resolvedContentNode.asText();
            }
        } catch (Exception e) {
            log.warn("解析 resolvedContent 失败: taskNo={}, error={}", task.getTaskNo(), e.getMessage());
        }
        return "";
    }

    private List<Map<String, String>> parseDispatchFilePathMappings(Task task) {
        if (task == null || !StringUtils.hasText(task.getConfigJson())) {
            return Collections.emptyList();
        }
        try {
            var configNode = objectMapper.readTree(task.getConfigJson());
            var aliasNode = configNode.get("aliasMap");
            if (aliasNode == null || !aliasNode.isObject()) {
                return Collections.emptyList();
            }

            Map<String, String> aliasMap = objectMapper.convertValue(
                    aliasNode,
                    new TypeReference<LinkedHashMap<String, String>>() {
                    });
            if (aliasMap == null || aliasMap.isEmpty()) {
                return Collections.emptyList();
            }

            List<Map<String, String>> mappings = new ArrayList<>();
            for (Map.Entry<String, String> entry : aliasMap.entrySet()) {
                String runtimePath = entry.getKey();
                String realPath = entry.getValue();
                if (!StringUtils.hasText(runtimePath) || !StringUtils.hasText(realPath)) {
                    continue;
                }
                mappings.add(Map.of(
                        "runtime_path", runtimePath,
                        "real_path", realPath));
            }
            return mappings;
        } catch (Exception e) {
            log.warn("解析 file_path_mappings 失败: taskNo={}, error={}", task.getTaskNo(), e.getMessage());
            return Collections.emptyList();
        }
    }

    private String buildRuntimeFilePath(WorkspaceFile file) {
        String fileId = file == null ? null : file.getFileId();
        if (!StringUtils.hasText(fileId)) {
            throw new IllegalArgumentException("文件标识缺失，无法生成容器路径");
        }
        String extension = normalizeFileExtension(file.getFileType());
        return buildWorkspaceFileBase(file) + fileId + extension;
    }

    private String buildWorkspaceFileBase(WorkspaceFile file) {
        return "WORKSTATION".equalsIgnoreCase(file.getSpaceType())
                ? "/workspace/workstation/original/"
                : "/workspace/user/original/";
    }

    private String normalizeFileExtension(String fileType) {
        if (!StringUtils.hasText(fileType)) {
            return "";
        }
        String cleaned = fileType.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (!StringUtils.hasText(cleaned)) {
            return "";
        }
        return "." + cleaned;
    }

    private RuntimeModeService.RuntimeSnapshot resolveTaskRuntimeSnapshot(
            Task task,
            com.fasterxml.jackson.databind.JsonNode configNode) {

        String runtimeModeRaw = readConfigText(configNode, "runtimeMode");
        if (!StringUtils.hasText(runtimeModeRaw)) {
            runtimeModeRaw = readConfigText(configNode, "podMode");
        }

        String runnerImageRaw = readConfigText(configNode, "runnerImage");
        if (!StringUtils.hasText(runnerImageRaw)) {
            runnerImageRaw = readConfigText(configNode, "runnerBaseImage");
        }

        RuntimeModeService.RuntimeSnapshot roleSnapshot = null;
        RuntimeModeService.RuntimeSnapshot runtimeSnapshot;
        if (StringUtils.hasText(runtimeModeRaw)) {
            String effectiveRunner = runnerImageRaw;
            if (!StringUtils.hasText(effectiveRunner) && "SIDECAR".equalsIgnoreCase(runtimeModeRaw)) {
                roleSnapshot = runtimeModeService.resolveForRole(task.getRoleId());
                effectiveRunner = roleSnapshot.getRunnerImage();
            }
            runtimeSnapshot = runtimeModeService.resolveFromRaw(
                    runtimeModeRaw,
                    effectiveRunner,
                    "taskNo=" + task.getTaskNo());
        } else {
            roleSnapshot = runtimeModeService.resolveForRole(task.getRoleId());
            runtimeSnapshot = roleSnapshot;
        }

        String zzModeRaw = readConfigText(configNode, "zzMode");
        if (StringUtils.hasText(zzModeRaw)
                && !zzModeRaw.equalsIgnoreCase(runtimeSnapshot.getZzMode())) {
            throw new IllegalStateException("任务运行模式与 zz 通道不一致: taskNo=" + task.getTaskNo());
        }

        return runtimeSnapshot;
    }

    private String readConfigText(com.fasterxml.jackson.databind.JsonNode node, String key) {
        if (node == null || key == null || !node.has(key) || node.get(key) == null) {
            return null;
        }
        String value = node.get(key).asText();
        return StringUtils.hasText(value) ? value : null;
    }

    private Map<String, Object> buildTaskReport(Task task, TaskCompleteRequest request, TaskStatus targetStatus, int finalTokensUsed) {
        TaskCompleteRequest.Report workerReport = request.getReport();
        List<String> estimatedOutput = parseEstimatedOutput(task);

        Map<String, Object> reportMap = new HashMap<>();
        reportMap.put("summary", resolveReportSummary(workerReport, targetStatus));
        reportMap.put("tokens", finalTokensUsed);
        reportMap.put("duration", formatDuration(request.getDurationMs()));
        reportMap.put("completion", resolveReportCompletion(workerReport, targetStatus));
        reportMap.put("audit", resolveReportAudit(workerReport, targetStatus));
        reportMap.put("artifacts", resolveReportArtifacts(workerReport, estimatedOutput));
        reportMap.put("branch", workerReport != null ? workerReport.getBranch() : null);
        reportMap.put("commit", workerReport != null ? workerReport.getCommit() : null);
        return reportMap;
    }

    private List<Object> resolveReportArtifacts(TaskCompleteRequest.Report workerReport, List<String> estimatedOutput) {
        if (workerReport != null && workerReport.getArtifacts() != null && !workerReport.getArtifacts().isEmpty()) {
            return new ArrayList<>(workerReport.getArtifacts());
        }

        LinkedHashSet<String> uniqueOutputs = new LinkedHashSet<>(estimatedOutput);
        List<Object> artifacts = new ArrayList<>();
        for (String outputCode : uniqueOutputs) {
            if (!StringUtils.hasText(outputCode)) {
                continue;
            }
            Map<String, String> artifact = buildFallbackArtifact(outputCode);
            if (!artifact.isEmpty()) {
                artifacts.add(artifact);
            }
        }

        if (artifacts.isEmpty()) {
            artifacts.add(buildFallbackArtifact(TaskOutputType.DIALOG_CONCLUSION.getCode()));
        }
        return artifacts;
    }

    private Map<String, String> buildFallbackArtifact(String outputCode) {
        Map<String, String> artifact = new HashMap<>();
        if (!StringUtils.hasText(outputCode)) {
            artifact.put("name", "dialog-conclusion.md");
            artifact.put("url", "/workspace/dialog-conclusion.md");
            return artifact;
        }

        switch (outputCode) {
            case "git_branch" -> {
                artifact.put("name", "git-branch.txt");
                artifact.put("url", "/workspace/git-branch.txt");
            }
            case "pull_request" -> {
                artifact.put("name", "pull-request.txt");
                artifact.put("url", "/workspace/pull-request.txt");
            }
            case "python_file" -> {
                artifact.put("name", "output.py");
                artifact.put("url", "/workspace/output.py");
            }
            case "java_file" -> {
                artifact.put("name", "Output.java");
                artifact.put("url", "/workspace/Output.java");
            }
            case "javascript_file" -> {
                artifact.put("name", "output.js");
                artifact.put("url", "/workspace/output.js");
            }
            case "typescript_file" -> {
                artifact.put("name", "output.ts");
                artifact.put("url", "/workspace/output.ts");
            }
            case "sql_file" -> {
                artifact.put("name", "output.sql");
                artifact.put("url", "/workspace/output.sql");
            }
            case "shell_script" -> {
                artifact.put("name", "output.sh");
                artifact.put("url", "/workspace/output.sh");
            }
            case "config_file" -> {
                artifact.put("name", "output.yaml");
                artifact.put("url", "/workspace/output.yaml");
            }
            case "txt" -> {
                artifact.put("name", "output.txt");
                artifact.put("url", "/workspace/output.txt");
            }
            case "markdown" -> {
                artifact.put("name", "output.md");
                artifact.put("url", "/workspace/output.md");
            }
            case "word" -> {
                artifact.put("name", "output.docx");
                artifact.put("url", "/workspace/output.docx");
            }
            case "excel" -> {
                artifact.put("name", "output.xlsx");
                artifact.put("url", "/workspace/output.xlsx");
            }
            case "ppt" -> {
                artifact.put("name", "output.pptx");
                artifact.put("url", "/workspace/output.pptx");
            }
            case "pdf" -> {
                artifact.put("name", "output.pdf");
                artifact.put("url", "/workspace/output.pdf");
            }
            case "json" -> {
                artifact.put("name", "output.json");
                artifact.put("url", "/workspace/output.json");
            }
            case "csv" -> {
                artifact.put("name", "output.csv");
                artifact.put("url", "/workspace/output.csv");
            }
            case "png" -> {
                artifact.put("name", "output.png");
                artifact.put("url", "/workspace/output.png");
            }
            case "archive" -> {
                artifact.put("name", "output.zip");
                artifact.put("url", "/workspace/output.zip");
            }
            case "api_call_result" -> {
                artifact.put("name", "api-result.json");
                artifact.put("url", "/workspace/api-result.json");
            }
            case "dialog_conclusion" -> {
                artifact.put("name", "dialog-conclusion.md");
                artifact.put("url", "/workspace/dialog-conclusion.md");
            }
            default -> {
                artifact.put("name", outputCode + ".txt");
                artifact.put("url", "/workspace/" + outputCode + ".txt");
            }
        }
        return artifact;
    }

    private String resolveReportSummary(TaskCompleteRequest.Report workerReport, TaskStatus targetStatus) {
        if (workerReport != null && StringUtils.hasText(workerReport.getSummary())) {
            return workerReport.getSummary();
        }
        return targetStatus == TaskStatus.COMPLETED ? "任务执行完成" : "任务执行失败";
    }

    private Integer resolveReportCompletion(TaskCompleteRequest.Report workerReport, TaskStatus targetStatus) {
        if (workerReport != null && workerReport.getCompletion() != null) {
            return workerReport.getCompletion();
        }
        return targetStatus == TaskStatus.COMPLETED ? 100 : 0;
    }

    private String resolveReportAudit(TaskCompleteRequest.Report workerReport, TaskStatus targetStatus) {
        if (workerReport != null && StringUtils.hasText(workerReport.getAudit())) {
            return workerReport.getAudit();
        }
        return targetStatus == TaskStatus.COMPLETED ? "A" : "C";
    }

    private List<String> parseEstimatedOutput(Task task) {
        if (task == null || !StringUtils.hasText(task.getConfigJson())) {
            return Collections.emptyList();
        }
        try {
            var configNode = objectMapper.readTree(task.getConfigJson());
            var estimatedOutputNode = configNode.get("estimatedOutput");
            if (estimatedOutputNode == null || !estimatedOutputNode.isArray()) {
                return Collections.emptyList();
            }
            return objectMapper.convertValue(estimatedOutputNode,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            log.warn("解析 estimatedOutput 失败: taskNo={}, error={}", task.getTaskNo(), e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<String> resolveOutputDomains(List<String> estimatedOutput) {
        if (estimatedOutput == null || estimatedOutput.isEmpty()) {
            return Collections.emptyList();
        }

        LinkedHashSet<String> domains = new LinkedHashSet<>();
        for (String outputCode : estimatedOutput) {
            TaskOutputType.fromCode(outputCode).map(TaskOutputType::getDomain).ifPresent(domains::add);
        }
        return new ArrayList<>(domains);
    }

    private List<Map<String, String>> buildDispatchGitConfig(Task task) {
        if (!StringUtils.hasText(task.getConfigJson())) {
            return Collections.emptyList();
        }

        try {
            var configNode = objectMapper.readTree(task.getConfigJson());
            var gitRepos = configNode.get("gitRepos");
            if (gitRepos == null || !gitRepos.isArray() || gitRepos.isEmpty()) {
                return Collections.emptyList();
            }

            List<Map<String, String>> result = new ArrayList<>();
            for (var repoNode : gitRepos) {
                String repo = repoNode.has("url") ? repoNode.get("url").asText("").trim() : "";
                String originBranch = repoNode.has("branch") ? repoNode.get("branch").asText("").trim() : "";
                if (!StringUtils.hasText(repo) || !StringUtils.hasText(originBranch)) {
                    log.warn("跳过无效 gitRepos 配置: taskNo={}, repo={}, branch={}",
                            task.getTaskNo(), repo, originBranch);
                    continue;
                }

                Map<String, String> gitConfig = new HashMap<>();
                gitConfig.put("repo", repo);
                gitConfig.put("origin_branch", originBranch);
                gitConfig.put("task_branch", "feat/" + task.getTaskNo());
                result.add(gitConfig);
            }
            return result;
        } catch (Exception e) {
            log.warn("解析任务 gitRepos 失败，忽略 git_config 下发: taskNo={}, error={}",
                    task.getTaskNo(), e.getMessage());
            return Collections.emptyList();
        }
    }

    private String resolveDispatchDeliveryMode(Task task, List<Map<String, String>> gitConfig) {
        String snapshotMode = parseDeliveryModeFromConfig(task);
        if (StringUtils.hasText(snapshotMode)) {
            return normalizeDeliveryMode(snapshotMode, task.getTaskNo());
        }
        return resolveDeliveryMode(parseEstimatedOutput(task), !gitConfig.isEmpty(), task.getTaskNo());
    }

    private String parseDeliveryModeFromConfig(Task task) {
        if (task == null || !StringUtils.hasText(task.getConfigJson())) {
            return "";
        }
        try {
            var configNode = objectMapper.readTree(task.getConfigJson());
            var deliveryModeNode = configNode.get("deliveryMode");
            if (deliveryModeNode != null && deliveryModeNode.isTextual()) {
                return deliveryModeNode.asText();
            }
        } catch (Exception e) {
            log.warn("解析 deliveryMode 失败: taskNo={}, error={}", task.getTaskNo(), e.getMessage());
        }
        return "";
    }

    private String resolveDeliveryMode(List<String> estimatedOutput, boolean hasGitConfig, String taskNo) {
        if (!hasGitConfig) {
            return DELIVERY_MODE_OSS;
        }

        if (containsGitDeliverySignal(estimatedOutput)) {
            return DELIVERY_MODE_GIT;
        }

        if (estimatedOutput == null || estimatedOutput.isEmpty()) {
            log.warn("estimatedOutput 为空但任务存在 git 配置，兼容回退为 git: taskNo={}", taskNo);
            return DELIVERY_MODE_GIT;
        }

        return DELIVERY_MODE_OSS;
    }

    private boolean containsGitDeliverySignal(List<String> estimatedOutput) {
        if (estimatedOutput == null || estimatedOutput.isEmpty()) {
            return false;
        }
        for (String outputCode : estimatedOutput) {
            if (OUTPUT_CODE_GIT_BRANCH.equalsIgnoreCase(outputCode)
                    || OUTPUT_CODE_PULL_REQUEST.equalsIgnoreCase(outputCode)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasValidRoleGitRepo(RoleEntity role) {
        if (role == null || role.getConfigJson() == null || role.getConfigJson().getGitRepos() == null) {
            return false;
        }
        return role.getConfigJson().getGitRepos().stream().anyMatch(
                gitRepo -> gitRepo != null
                        && StringUtils.hasText(gitRepo.getUrl())
                        && StringUtils.hasText(gitRepo.getBranch()));
    }

    private String normalizeDeliveryMode(String rawMode, String taskNo) {
        if (!StringUtils.hasText(rawMode)) {
            return DELIVERY_MODE_OSS;
        }
        String mode = rawMode.trim().toLowerCase();
        if (DELIVERY_MODE_GIT.equals(mode) || DELIVERY_MODE_OSS.equals(mode)) {
            return mode;
        }
        log.warn("未知 deliveryMode，回退为 oss: taskNo={}, rawMode={}", taskNo, rawMode);
        return DELIVERY_MODE_OSS;
    }

    private record TaskInputFileRef(
            String fileId,
            String originalFileName,
            String runtimePath,
            String realPath) {
    }

    private record PromptLayers(
            String rolePrompt,
            String platformPrompt,
            String userSoul) {
    }

    private record TerminateEnqueueResult(String queueKey, String requestId) {}

    private void publishTaskEvent(String streamKey, String eventType, String taskNo, Map<String, Object> data) {
        Map<String, String> fields = new HashMap<>();
        fields.put("event_type", eventType);
        fields.put("task_no", taskNo);
        fields.put("timestamp", LocalDateTime.now().toString());

        if (data != null && !data.isEmpty()) {
            try {
                fields.put("data", objectMapper.writeValueAsString(data));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("序列化任务事件失败: eventType=" + eventType + ", taskNo=" + taskNo, e);
            }
        }

        redisTemplate.opsForStream().add(streamKey, fields);
    }

    /**
     * 格式化时长
     */
    private String formatDuration(Long durationMs) {
        if (durationMs == null || durationMs == 0) {
            return "0s";
        }
        long seconds = durationMs / 1000;
        if (seconds < 60) {
            return seconds + "s";
        }
        long minutes = seconds / 60;
        seconds = seconds % 60;
        return minutes + "m " + seconds + "s";
    }
}
