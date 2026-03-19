package com.linkwork.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkwork.config.DispatchConfig;
import com.linkwork.model.dto.TaskCompleteRequest;
import com.linkwork.model.entity.LinkworkTask;
import com.linkwork.model.enums.TaskStatus;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sync task status from worker stream events to DB.
 * This mirrors the original general_agent behavior: realtime sync + periodic reconciliation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskStatusSyncService {

    private static final Set<TaskStatus> TERMINAL_STATES = Set.of(
            TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.ABORTED);
    private static final List<TaskStatus> ACTIVE_STATES = List.of(
            TaskStatus.PENDING, TaskStatus.RUNNING, TaskStatus.PENDING_AUTH);
    private static final int TASK_SCAN_PAGE_SIZE = 200;

    private final TaskV1Service taskService;
    private final StringRedisTemplate redisTemplate;
    private final DispatchConfig dispatchConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentHashMap<String, TaskStatus> syncedStatus = new ConcurrentHashMap<>();

    @PostConstruct
    public void syncHistoryOnStartup() {
        Thread.ofVirtual().name("task-status-sync-init").start(() -> {
            try {
                Thread.sleep(5000);
                syncActiveTasks("startup");
            } catch (Exception e) {
                log.error("task status startup sync failed", e);
            }
        });
    }

    @Scheduled(fixedDelayString = "${linkwork.task-status-sync.scan-interval-ms:15000}")
    public void syncHistoryPeriodically() {
        syncActiveTasks("schedule");
    }

    private void syncActiveTasks(String trigger) {
        int scanned = 0;
        int synced = 0;
        for (TaskStatus status : ACTIVE_STATES) {
            long current = 1;
            while (true) {
                Page<LinkworkTask> page = taskService.listTasks(null, status.name(), (int) current, TASK_SCAN_PAGE_SIZE, null);
                List<LinkworkTask> records = page.getRecords();
                if (records == null || records.isEmpty()) {
                    break;
                }
                scanned += records.size();
                for (LinkworkTask task : records) {
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
            log.info("task status reconciliation done: trigger={}, synced={}, scanned={}", trigger, synced, scanned);
        } else if ("startup".equals(trigger)) {
            log.info("task status startup reconciliation done: scanned={}", scanned);
        }
    }

    private boolean syncSingleTask(LinkworkTask task, String trigger) {
        TaskStatus currentStatus = task.getStatus();
        TaskStatus resolved = resolveStatusFromStream(task.getTaskNo(), task.getWorkstationId());
        if (resolved == null || resolved == currentStatus) {
            return false;
        }
        try {
            UsageSnapshot usageSnapshot = resolveUsageFromStream(task.getTaskNo(), task.getWorkstationId());
            persistStatusWithUsage(
                    task.getTaskNo(),
                    resolved,
                    usageSnapshot.tokensUsed(),
                    usageSnapshot.durationMs(),
                    "sync-" + trigger
            );
            syncedStatus.put(task.getTaskNo(), resolved);
            log.info("task status reconciled: trigger={}, taskNo={}, {} -> {}",
                    trigger, task.getTaskNo(), currentStatus, resolved);
            return true;
        } catch (Exception e) {
            log.error("task status reconcile failed: trigger={}, taskNo={}, from={}, to={}",
                    trigger, task.getTaskNo(), currentStatus, resolved, e);
            return false;
        }
    }

    public void onEvent(String taskNo, Map<String, Object> eventData) {
        if (taskNo == null || taskNo.isBlank() || eventData == null) {
            return;
        }
        String eventType = String.valueOf(eventData.getOrDefault("event_type", ""));
        TaskStatus targetStatus = resolveTargetStatus(eventType, eventData);
        if (targetStatus == null) {
            return;
        }
        try {
            Integer tokensUsed = resolveTokensUsedFromEvent(eventData);
            Long durationMs = resolveDurationMsFromEvent(eventData);

            LinkworkTask currentTask = taskService.getTaskByNo(taskNo);
            TaskStatus currentStatus = currentTask.getStatus();
            boolean shouldSyncStatus = shouldUpdateStatus(currentStatus, targetStatus);
            boolean shouldBackfillUsage = shouldBackfillUsage(currentTask, tokensUsed, durationMs);
            if (!shouldSyncStatus && !shouldBackfillUsage) {
                return;
            }

            TaskStatus statusToPersist = shouldSyncStatus ? targetStatus : currentStatus;
            persistStatusWithUsage(taskNo, statusToPersist, tokensUsed, durationMs, "event");
            syncedStatus.put(taskNo, statusToPersist);
            log.info("task status synced from event: taskNo={}, eventType={}, status={}",
                    taskNo, eventType, statusToPersist);

            if (TERMINAL_STATES.contains(statusToPersist)) {
                Thread.ofVirtual().start(() -> {
                    try {
                        Thread.sleep(300_000);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    syncedStatus.remove(taskNo);
                });
            }
        } catch (Exception e) {
            log.error("task status sync from event failed: taskNo={}, targetStatus={}", taskNo, targetStatus, e);
        }
    }

    private void persistStatusWithUsage(String taskNo,
                                        TaskStatus statusToPersist,
                                        Integer tokensUsed,
                                        Long durationMs,
                                        String trigger) {
        int safeTokens = tokensUsed != null && tokensUsed >= 0 ? tokensUsed : 0;
        long safeDurationMs = durationMs != null && durationMs >= 0 ? durationMs : 0L;

        if (statusToPersist == TaskStatus.COMPLETED || statusToPersist == TaskStatus.FAILED) {
            try {
                LinkworkTask current = taskService.getTaskByNo(taskNo);
                if (current.getStatus() == TaskStatus.PENDING
                        || current.getStatus() == TaskStatus.RUNNING
                        || current.getStatus() == TaskStatus.PENDING_AUTH) {
                    TaskCompleteRequest completeRequest = new TaskCompleteRequest();
                    completeRequest.setStatus(statusToPersist.name());
                    completeRequest.setTokensUsed(safeTokens);
                    completeRequest.setDurationMs(safeDurationMs);
                    taskService.completeTask(taskNo, completeRequest);
                    log.info("task status sync used completeTask: trigger={}, taskNo={}, status={}, tokens={}, durationMs={}",
                            trigger, taskNo, statusToPersist, safeTokens, safeDurationMs);
                    return;
                }
            } catch (Exception ex) {
                log.warn("task status sync completeTask failed, fallback to updateStatusWithUsage: trigger={}, taskNo={}, status={}, err={}",
                        trigger, taskNo, statusToPersist, ex.getMessage());
            }
        }
        taskService.updateStatusWithUsage(taskNo, statusToPersist, safeTokens, safeDurationMs);
    }

    private TaskStatus resolveStatusFromStream(String taskNo, Long workstationId) {
        List<String> streamKeys = List.of(
                dispatchConfig.getLogStreamKey(workstationId, taskNo),
                "stream:task:" + taskNo + ":events",
                "stream:task:" + taskNo
        );
        TaskStatus best = null;
        for (String streamKey : streamKeys) {
            try {
                List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                        .read(StreamOffset.fromStart(streamKey));
                if (records == null || records.isEmpty()) {
                    continue;
                }
                for (MapRecord<String, Object, Object> record : records) {
                    Map<String, Object> eventData = extractEventData(record);
                    String eventType = String.valueOf(eventData.getOrDefault("event_type", ""));
                    TaskStatus status = resolveTargetStatus(eventType, eventData);
                    if (shouldUpdateStatus(best, status)) {
                        best = status;
                    }
                }
            } catch (Exception e) {
                log.debug("read stream failed while resolving status: streamKey={}, err={}", streamKey, e.getMessage());
            }
        }
        return best;
    }

    private UsageSnapshot resolveUsageFromStream(String taskNo, Long workstationId) {
        List<String> streamKeys = List.of(
                dispatchConfig.getLogStreamKey(workstationId, taskNo),
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
                log.debug("read stream failed while resolving usage: streamKey={}, err={}", streamKey, e.getMessage());
            }
        }
        return new UsageSnapshot(tokensUsed, durationMs);
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
                log.debug("parse payload failed, fallback flat event: {}", e.getMessage());
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

    private TaskStatus resolveTargetStatus(String eventType, Map<String, Object> eventData) {
        String normalizedEventType = eventType == null ? "" : eventType.trim().toUpperCase(Locale.ROOT);
        return switch (normalizedEventType) {
            case "TASK_ASSIGNED", "TASK_STARTED", "SESSION_START" -> TaskStatus.RUNNING;
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
                yield null;
            }
            case "TASK_COMPLETED" -> TaskStatus.COMPLETED;
            case "TASK_FAILED" -> TaskStatus.FAILED;
            case "TASK_ABORTED", "TASK_TERMINATED" -> TaskStatus.ABORTED;
            case "WORKSPACE_ARCHIVED" -> {
                String archivedStatus = String.valueOf(eventData.getOrDefault("status", "")).toLowerCase(Locale.ROOT);
                if ("failed".equals(archivedStatus)) {
                    yield TaskStatus.FAILED;
                }
                if ("completed".equals(archivedStatus) || "success".equals(archivedStatus)) {
                    yield TaskStatus.COMPLETED;
                }
                if ("aborted".equals(archivedStatus) || "cancelled".equals(archivedStatus) || "canceled".equals(archivedStatus)) {
                    yield TaskStatus.ABORTED;
                }
                yield null;
            }
            case "TASK_ABORT_ACK" -> TaskStatus.RUNNING;
            case "TOOL_CALL", "TOOL_RESULT", "THINKING", "ASSISTANT_TEXT", "SECURITY_ALLOW", "SECURITY_DENY" ->
                    TaskStatus.RUNNING;
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

    private boolean shouldBackfillUsage(LinkworkTask task, Integer eventTokensUsed, Long eventDurationMs) {
        if (task == null) {
            return false;
        }
        boolean canBackfillTokens = eventTokensUsed != null
                && eventTokensUsed >= 0
                && (task.getTokensUsed() == null || task.getTokensUsed() <= 0);
        boolean canBackfillDuration = eventDurationMs != null
                && eventDurationMs > 0
                && (task.getDurationMs() == null || task.getDurationMs() <= 0);
        return canBackfillTokens || canBackfillDuration;
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

    private record UsageSnapshot(Integer tokensUsed, Long durationMs) {
    }
}
