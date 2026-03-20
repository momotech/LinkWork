package com.linkwork.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkwork.config.DispatchConfig;
import com.linkwork.model.dto.TaskCompleteRequest;
import com.linkwork.model.dto.TaskResponse;
import com.linkwork.model.entity.Task;
import com.linkwork.model.enums.TaskStatus;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务状态同步服务
 * 
 * 监听 Redis Stream 中的关键事件，将任务状态同步到数据库。
 * 
 * 背景：momo-worker (外部 Agent 执行器) 执行任务时只向 Redis Stream 写入事件，
 * 不会回调后端更新数据库。导致数据库中的任务状态永远停留在 PENDING。
 * 
 * 本服务既在 WebSocket 转发事件时实时同步，也通过后台定时扫描做兜底补偿，
 * 避免无人订阅时终态无法落库。
 * 
 * 事件 → 状态映射：
 *   TASK_STARTED / SESSION_START  → RUNNING
 *   TASK_COMPLETED / SESSION_END (exit_code=0) → COMPLETED
 *   TASK_FAILED / SESSION_END (exit_code!=0)   → FAILED
 *   TASK_ABORTED                               → ABORTED
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskStatusSyncService {

    private final TaskService taskService;
    private final StringRedisTemplate redisTemplate;
    private final DispatchConfig dispatchConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 启动时扫描数据库中 PENDING / RUNNING 状态的任务，
     * 从 Redis Stream 中读取历史事件补齐状态。
     */
    @PostConstruct
    public void syncHistoryOnStartup() {
        Thread.ofVirtual().name("task-status-sync-init").start(() -> {
            try {
                // 等待 Spring 容器完全启动
                Thread.sleep(5000);
                syncActiveTasks("startup");
            } catch (Exception e) {
                log.error("启动时状态补齐异常", e);
            }
        });
    }

    /**
     * 后台常驻扫描（不依赖 WebSocket 订阅）：
     * 周期性补齐 PENDING / RUNNING 任务状态，避免终态长期未落库。
     */
    @Scheduled(fixedDelayString = "${robot.task-status-sync.scan-interval-ms:15000}")
    public void syncHistoryPeriodically() {
        syncActiveTasks("schedule");
    }

    private void syncActiveTasks(String trigger) {
        int scanned = 0;
        int synced = 0;

        for (TaskStatus status : ACTIVE_STATES) {
            long current = 1;
            while (true) {
                Page<Task> page = taskService.listTasks(null, status.name(), (int) current, TASK_SCAN_PAGE_SIZE);
                List<Task> records = page.getRecords();
                if (records == null || records.isEmpty()) {
                    break;
                }
                scanned += records.size();
                for (Task task : records) {
                    if (syncSingleTask(task, trigger)) {
                        synced++;
                    }
                }
                if (current >= page.getPages()) {
                    break;
                }
                current++;
            }
        }

        if (synced > 0) {
            log.info("任务状态补齐完成: trigger={}, synced={}, scanned={}", trigger, synced, scanned);
        } else if ("startup".equals(trigger)) {
            log.info("启动补齐完成: trigger={}, synced=0, scanned={}", trigger, scanned);
        }
    }

    private boolean syncSingleTask(Task task, String trigger) {
        TaskStatus currentStatus = task.getStatus();
        TaskStatus resolved = resolveStatusFromStream(task.getTaskNo());
        if (resolved == null || resolved == currentStatus) {
            return false;
        }
        try {
            UsageSnapshot usageSnapshot = resolveUsageFromStream(task.getTaskNo());
            PersistResult persistResult = persistStatusWithUsage(
                    task.getTaskNo(),
                    resolved,
                    usageSnapshot.tokensUsed(),
                    usageSnapshot.durationMs(),
                    "sync-" + trigger);
            Task updatedTask = persistResult.task();
            syncedStatus.put(task.getTaskNo(), resolved);
            if (!persistResult.notifiedByCompleteFlow()) {
                log.debug("任务终态由同步流程补齐: trigger={}, taskNo={}, status={}",
                        trigger, task.getTaskNo(), resolved);
            }
            log.info("任务状态补齐: trigger={}, taskNo={}, {} -> {}",
                    trigger, task.getTaskNo(), currentStatus, resolved);
            return true;
        } catch (Exception e) {
            log.error("补齐任务状态失败: trigger={}, taskNo={}, from={}, to={}",
                    trigger, task.getTaskNo(), currentStatus, resolved, e);
            return false;
        }
    }

    /**
     * 从 Redis Stream 历史事件中推断任务的最终状态
     */
    private TaskStatus resolveStatusFromStream(String taskNo) {
        Long roleId = resolveRoleId(taskNo);
        List<String> streamKeys = List.of(
                dispatchConfig.getLogStreamKey(roleId, taskNo),
                "stream:task:" + taskNo + ":events",
                "stream:task:" + taskNo
        );

        TaskStatus best = null;
        for (String streamKey : streamKeys) {
            try {
                List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                        .read(StreamOffset.fromStart(streamKey));
                if (records == null || records.isEmpty()) continue;

                for (MapRecord<String, Object, Object> record : records) {
                    Map<String, Object> eventData = extractEventData(record);

                    String eventType = String.valueOf(eventData.getOrDefault("event_type", ""));
                    TaskStatus status = resolveTargetStatus(eventType, eventData);
                    if (shouldUpdateStatus(best, status)) {
                        best = status;
                    }
                }
            } catch (Exception e) {
                log.debug("读取 Stream {} 失败: {}", streamKey, e.getMessage());
            }
        }
        return best;
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
                    } catch (Exception ignored) {}
                }
                Object payloadData = payloadMap.get("data");
                if (payloadData instanceof Map<?, ?> dataMap) {
                    dataMap.forEach((k, v) -> payloadMap.putIfAbsent(String.valueOf(k), v));
                }
                return payloadMap;
            } catch (Exception e) {
                log.debug("解析 payload 失败，回退扁平事件: {}", e.getMessage());
            }
        }

        Object dataObj = rawEvent.get("data");
        if (dataObj instanceof String dataStr && (dataStr.startsWith("{") || dataStr.startsWith("["))) {
            try {
                rawEvent.put("data", objectMapper.readValue(dataStr, Object.class));
            } catch (Exception ignored) {}
        }

        Object flatData = rawEvent.get("data");
        if (flatData instanceof Map<?, ?> dataMap) {
            dataMap.forEach((k, v) -> rawEvent.putIfAbsent(String.valueOf(k), v));
        }
        return rawEvent;
    }

    private Long resolveRoleId(String taskNo) {
        try {
            Task task = taskService.getTaskByNo(taskNo);
            return task.getRoleId();
        } catch (Exception e) {
            log.debug("解析任务 roleId 失败，使用空 roleId 路由: taskNo={}", taskNo);
            return null;
        }
    }

    /**
     * 记录每个任务已同步到的最终状态，避免重复更新和状态回退。
     * key=taskNo, value=已同步的最高优先级状态
     */
    private final ConcurrentHashMap<String, TaskStatus> syncedStatus = new ConcurrentHashMap<>();

    /**
     * 终态集合：任务到达这些状态后不再变更
     */
    private static final Set<TaskStatus> TERMINAL_STATES = Set.of(
            TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.ABORTED
    );
    private static final List<TaskStatus> ACTIVE_STATES = List.of(TaskStatus.PENDING, TaskStatus.RUNNING);
    private static final int TASK_SCAN_PAGE_SIZE = 200;

    /**
     * 处理一条 Redis Stream 事件，判断是否需要同步任务状态
     *
     * @param taskNo    任务编号
     * @param eventData 事件数据（从 Redis Stream record 解析）
     */
    public void onEvent(String taskNo, Map<String, Object> eventData) {
        if (taskNo == null || taskNo.isEmpty() || eventData == null) return;

        String eventType = String.valueOf(eventData.getOrDefault("event_type", ""));
        TaskStatus targetStatus = resolveTargetStatus(eventType, eventData);

        if (targetStatus == null) return;

        // 执行数据库更新
        try {
            Integer tokensUsed = resolveTokensUsedFromEvent(eventData);
            Long durationMs = resolveDurationMsFromEvent(eventData);

            Task currentTask = taskService.getTaskByNo(taskNo);
            TaskStatus currentStatus = currentTask.getStatus();
            boolean shouldSyncStatus = shouldUpdateStatus(currentStatus, targetStatus);
            boolean shouldBackfillUsage = shouldBackfillUsage(currentTask, tokensUsed, durationMs);

            // 历史任务详情反复打开会重放 Stream 事件，这里必须幂等避免重复通知。
            if (!shouldSyncStatus && !shouldBackfillUsage) {
                return;
            }

            TaskStatus statusToPersist = shouldSyncStatus ? targetStatus : currentStatus;
            PersistResult persistResult = persistStatusWithUsage(taskNo, statusToPersist, tokensUsed, durationMs, "event");
            Task updatedTask = persistResult.task();
            syncedStatus.put(taskNo, statusToPersist);
            if (shouldSyncStatus && !persistResult.notifiedByCompleteFlow()) {
                log.debug("任务终态由事件同步补齐: taskNo={}, status={}", updatedTask.getTaskNo(), statusToPersist);
            }
            log.info("任务状态已同步: taskNo={}, event={}, status={}", taskNo, eventType, targetStatus);

            // 如果到达终态，延迟清理内存（避免内存泄漏）
            if (TERMINAL_STATES.contains(statusToPersist)) {
                // 保留 5 分钟后清理，防止短期内重复事件触发
                Thread.ofVirtual().start(() -> {
                    try {
                        Thread.sleep(300_000); // 5 min
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    syncedStatus.remove(taskNo);
                });
            }
        } catch (Exception e) {
            log.error("同步任务状态失败: taskNo={}, targetStatus={}", taskNo, targetStatus, e);
        }
    }

    private PersistResult persistStatusWithUsage(String taskNo,
                                                 TaskStatus statusToPersist,
                                                 Integer tokensUsed,
                                                 Long durationMs,
                                                 String trigger) {
        Integer safeTokens = tokensUsed != null && tokensUsed >= 0 ? tokensUsed : 0;
        Long safeDurationMs = durationMs != null && durationMs >= 0 ? durationMs : 0L;

        if (statusToPersist == TaskStatus.COMPLETED || statusToPersist == TaskStatus.FAILED) {
            try {
                Task currentTask = taskService.getTaskByNo(taskNo);
                if (currentTask.getStatus() == TaskStatus.PENDING
                        || currentTask.getStatus() == TaskStatus.RUNNING
                        || currentTask.getStatus() == TaskStatus.PENDING_AUTH) {
                    TaskCompleteRequest completeRequest = new TaskCompleteRequest();
                    completeRequest.setStatus(statusToPersist.name());
                    completeRequest.setTokensUsed(safeTokens);
                    completeRequest.setDurationMs(safeDurationMs);
                    Task completedTask = taskService.completeTask(taskNo, completeRequest);
                    log.info("状态同步触发 completeTask 补全: trigger={}, taskNo={}, status={}, tokens={}, durationMs={}",
                            trigger, taskNo, statusToPersist, safeTokens, safeDurationMs);
                    return new PersistResult(completedTask, true);
                }
            } catch (Exception ex) {
                log.warn("状态同步 completeTask 补全失败，降级 updateStatusWithUsage: trigger={}, taskNo={}, status={}, err={}",
                        trigger, taskNo, statusToPersist, ex.getMessage());
            }
        }
        Task updatedTask = taskService.updateStatusWithUsage(taskNo, statusToPersist, safeTokens, safeDurationMs);
        return new PersistResult(updatedTask, false);
    }

    private boolean shouldBackfillUsage(Task task, Integer eventTokensUsed, Long eventDurationMs) {
        if (task == null) {
            return false;
        }
        if (TERMINAL_STATES.contains(task.getStatus()) && needsBillingBackfill(task)) {
            return true;
        }
        boolean canBackfillTokens = eventTokensUsed != null
                && eventTokensUsed >= 0
                && (task.getTokensUsed() == null || task.getTokensUsed() <= 0);
        boolean canBackfillDuration = eventDurationMs != null
                && eventDurationMs > 0
                && (task.getDurationMs() == null || task.getDurationMs() <= 0);
        return canBackfillTokens || canBackfillDuration;
    }

    private boolean needsBillingBackfill(Task task) {
        boolean missingTokens = task.getTokensUsed() == null || task.getTokensUsed() <= 0;
        boolean missingUsageFields = task.getInputTokens() == null
                || task.getOutputTokens() == null
                || task.getRequestCount() == null
                || task.getTokenLimit() == null
                || task.getUsagePercent() == null;
        return missingTokens || missingUsageFields;
    }

    private UsageSnapshot resolveUsageFromStream(String taskNo) {
        Long roleId = resolveRoleId(taskNo);
        List<String> streamKeys = List.of(
                dispatchConfig.getLogStreamKey(roleId, taskNo),
                "stream:task:" + taskNo + ":events",
                "stream:task:" + taskNo
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
                    Integer eventTokens = resolveTokensUsedFromEvent(eventData);
                    Long eventDuration = resolveDurationMsFromEvent(eventData);
                    if (eventTokens != null && eventTokens >= 0) {
                        tokensUsed = eventTokens;
                    }
                    if (eventDuration != null && eventDuration > 0) {
                        durationMs = eventDuration;
                    }
                }
            } catch (Exception e) {
                log.debug("读取 Stream usage 失败: streamKey={}, error={}", streamKey, e.getMessage());
            }
        }

        return new UsageSnapshot(tokensUsed, durationMs);
    }

    private Integer resolveTokensUsedFromEvent(Map<String, Object> eventData) {
        Long value = resolveLongByKeys(eventData, "tokens_used", "tokensUsed", "token_usage");
        if (value == null || value < 0 || value > Integer.MAX_VALUE) {
            return null;
        }
        return value.intValue();
    }

    private Long resolveDurationMsFromEvent(Map<String, Object> eventData) {
        return resolveLongByKeys(eventData, "duration_ms", "durationMs", "elapsed_ms");
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

    private record UsageSnapshot(Integer tokensUsed, Long durationMs) {}
    private record PersistResult(Task task, boolean notifiedByCompleteFlow) {}

    /**
     * 根据事件类型和数据推断目标任务状态
     */
    private TaskStatus resolveTargetStatus(String eventType, Map<String, Object> eventData) {
        String normalizedEventType = eventType == null ? "" : eventType.trim().toUpperCase(Locale.ROOT);
        return switch (normalizedEventType) {
            // → RUNNING
            case "TASK_STARTED", "SESSION_START" -> TaskStatus.RUNNING;

            // → COMPLETED or FAILED（根据 exit_code 判断）
            case "SESSION_END" -> {
                Object exitCodeObj = eventData.get("exit_code");
                if (exitCodeObj != null) {
                    try {
                        int exitCode = Integer.parseInt(String.valueOf(exitCodeObj));
                        yield exitCode == 0 ? TaskStatus.COMPLETED : TaskStatus.FAILED;
                    } catch (NumberFormatException e) {
                        yield null;
                    }
                }
                // SESSION_END 未携带 exit_code 时不推断终态，等待 TASK_COMPLETED/TASK_FAILED/WORKSPACE_ARCHIVED
                yield null;
            }

            // → COMPLETED
            case "TASK_COMPLETED" -> TaskStatus.COMPLETED;

            // → FAILED
            case "TASK_FAILED" -> TaskStatus.FAILED;

            // → ABORTED
            case "TASK_ABORTED", "TASK_TERMINATED" -> TaskStatus.ABORTED;

            // WORKSPACE 归档事件，status=failed/completed/aborted
            case "WORKSPACE_ARCHIVED" -> {
                String archivedStatus = String.valueOf(eventData.getOrDefault("status", "")).toLowerCase(Locale.ROOT);
                if ("failed".equals(archivedStatus)) {
                    yield TaskStatus.FAILED;
                }
                if ("completed".equals(archivedStatus) || "success".equals(archivedStatus)) {
                    yield TaskStatus.COMPLETED;
                }
                if ("aborted".equals(archivedStatus) || "cancelled".equals(archivedStatus)
                        || "canceled".equals(archivedStatus)) {
                    yield TaskStatus.ABORTED;
                }
                yield null;
            }

            // → RUNNING（终止请求已被执行端接收，但尚未完成终止）
            case "TASK_ABORT_ACK" -> TaskStatus.RUNNING;

            // 其他事件：如果是首次出现的工具调用/思考等事件，说明任务已开始执行
            case "TOOL_CALL", "TOOL_RESULT", "THINKING", "ASSISTANT_TEXT",
                 "SECURITY_ALLOW", "SECURITY_DENY" -> TaskStatus.RUNNING;

            // 不需要同步的事件
            default -> null;
        };
    }

    private boolean shouldUpdateStatus(TaskStatus current, TaskStatus target) {
        if (target == null) {
            return false;
        }
        if (current == null) {
            return true;
        }
        if (current == target) {
            return false;
        }
        return statusPriority(target) > statusPriority(current);
    }

    private int statusPriority(TaskStatus status) {
        return switch (status) {
            case PENDING -> 0;
            case RUNNING, PENDING_AUTH -> 10;
            case COMPLETED -> 20;
            case FAILED -> 30;
            case ABORTED -> 40;
        };
    }
}
