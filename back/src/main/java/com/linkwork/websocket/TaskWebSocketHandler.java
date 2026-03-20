package com.linkwork.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkwork.config.DispatchConfig;
import com.linkwork.service.NfsStorageService;
import com.linkwork.service.TaskEventBroadcaster;
import com.linkwork.service.TaskOutputWorkspaceSyncService;
import com.linkwork.service.TaskPathlistSyncService;
import com.linkwork.service.TaskService;
import com.linkwork.service.TaskStatusSyncService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 任务 WebSocket 事件推送。
 *
 * 说明：
 * - Redis Stream 的实时消费职责已下沉到 TaskEventConsumerService
 * - 本 Handler 仅负责：会话绑定、历史回放、实时广播推送
 */
@Slf4j
@Component
public class TaskWebSocketHandler extends TextWebSocketHandler {
    private static final Pattern NUMERIC_ID_PATTERN = Pattern.compile("^\\d+$");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final StringRedisTemplate redisTemplate;
    private final TaskStatusSyncService taskStatusSyncService;
    private final TaskService taskService;
    private final DispatchConfig dispatchConfig;
    private final NfsStorageService nfsStorageService;
    private final TaskOutputWorkspaceSyncService taskOutputWorkspaceSyncService;
    private final TaskPathlistSyncService taskPathlistSyncService;
    private final TaskEventBroadcaster taskEventBroadcaster;

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> sessionTaskMap = new ConcurrentHashMap<>();
    private final Map<String, String> taskWorkstationCache = new ConcurrentHashMap<>();

    private String broadcasterListenerId;

    public TaskWebSocketHandler(StringRedisTemplate redisTemplate,
                                TaskStatusSyncService taskStatusSyncService,
                                TaskService taskService,
                                DispatchConfig dispatchConfig,
                                NfsStorageService nfsStorageService,
                                TaskOutputWorkspaceSyncService taskOutputWorkspaceSyncService,
                                TaskPathlistSyncService taskPathlistSyncService,
                                TaskEventBroadcaster taskEventBroadcaster) {
        this.redisTemplate = redisTemplate;
        this.taskStatusSyncService = taskStatusSyncService;
        this.taskService = taskService;
        this.dispatchConfig = dispatchConfig;
        this.nfsStorageService = nfsStorageService;
        this.taskOutputWorkspaceSyncService = taskOutputWorkspaceSyncService;
        this.taskPathlistSyncService = taskPathlistSyncService;
        this.taskEventBroadcaster = taskEventBroadcaster;
    }

    @PostConstruct
    public void registerBroadcaster() {
        broadcasterListenerId = taskEventBroadcaster.register(this::broadcastToTask);
    }

    @PreDestroy
    public void unregisterBroadcaster() {
        taskEventBroadcaster.unregister(broadcasterListenerId);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.put(session.getId(), session);
        String taskId = extractTaskId(session);
        log.info("WebSocket connected: {}, taskId: {}", session.getId(), taskId);
        if (taskId != null) {
            bindTask(session, taskId);
        }
    }

    private String extractTaskId(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri != null && uri.getQuery() != null) {
            for (String param : uri.getQuery().split("&")) {
                String[] kv = param.split("=");
                if (kv.length == 2 && "taskId".equals(kv[0])) {
                    return kv[1];
                }
            }
        }
        return null;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Map<String, Object> request = objectMapper.readValue(message.getPayload(), Map.class);
        String action = (String) request.get("action");
        String taskId = firstNonBlank(request, "taskId", "taskNo", "task_id");
        log.info("Received message: action={}, taskId={}", action, taskId);
        if ("bind".equals(action) && taskId != null) {
            bindTask(session, taskId);
        }
    }

    private void bindTask(WebSocketSession session, String taskId) {
        String taskNo = normalizeTaskNo(taskId);
        sessionTaskMap.put(session.getId(), taskNo);
        if (!taskNo.equals(taskId)) {
            log.info("Session {} bound task normalized: rawTaskId={}, taskNo={}", session.getId(), taskId, taskNo);
        } else {
            log.info("Session {} bound to task {}", session.getId(), taskNo);
        }
        pushHistoryEvents(session, taskNo);
    }

    private List<String> buildStreamKeys(String taskId) {
        List<String> keys = new ArrayList<>();
        Long roleId = resolveRoleId(taskId);
        keys.add(dispatchConfig.getLogStreamKey(roleId, taskId));
        keys.add("stream:task:" + taskId);
        keys.add("stream:task:" + taskId + ":events");
        keys.add("stream:build:" + taskId);
        return keys;
    }

    private Long resolveRoleId(String taskId) {
        try {
            return taskService.getTaskByNo(taskId).getRoleId();
        } catch (Exception e) {
            log.debug("WebSocket resolve roleId failed, fallback null: taskId={}", taskId);
            return null;
        }
    }

    private String normalizeTaskNo(String rawTaskId) {
        if (!StringUtils.hasText(rawTaskId)) {
            return rawTaskId;
        }
        String candidate = rawTaskId.trim();
        try {
            return taskService.getTaskByNo(candidate).getTaskNo();
        } catch (Exception ignored) {
            // ignore and continue resolving by numeric id
        }

        if (NUMERIC_ID_PATTERN.matcher(candidate).matches()) {
            try {
                return taskService.getTask(Long.parseLong(candidate)).getTaskNo();
            } catch (Exception e) {
                log.debug("WebSocket normalize taskId by numeric id failed: taskId={}, err={}",
                        candidate, e.getMessage());
            }
        }
        return candidate;
    }

    private void pushHistoryEvents(WebSocketSession session, String taskId) {
        try {
            List<String> streamKeys = buildStreamKeys(taskId);
            Set<String> sentIds = new HashSet<>();

            for (String streamKey : streamKeys) {
                try {
                    List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                            .read(StreamOffset.fromStart(streamKey));
                    if (records == null || records.isEmpty()) {
                        continue;
                    }
                    log.info("Push {} history events for task {} from {}", records.size(), taskId, streamKey);
                    for (MapRecord<String, Object, Object> record : records) {
                        String recordId = record.getId().getValue();
                        if (sentIds.contains(recordId)) {
                            continue;
                        }
                        sentIds.add(recordId);
                        syncTaskStatus(taskId, record);
                        sendEvent(session, record);
                    }
                } catch (Exception e) {
                    log.debug("history stream read skipped: streamKey={}, err={}", streamKey, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("push history events failed: taskId={}", taskId, e);
        }
    }

    private void broadcastToTask(String taskId, MapRecord<String, Object, Object> record) {
        sessionTaskMap.forEach((sessionId, tid) -> {
            if (!taskId.equals(tid)) {
                return;
            }
            WebSocketSession session = sessions.get(sessionId);
            if (session != null && session.isOpen()) {
                sendEvent(session, record);
            }
        });
    }

    private void syncTaskStatus(String taskId, MapRecord<String, Object, Object> record) {
        try {
            Map<String, Object> eventData = extractEventData(record);
            taskStatusSyncService.onEvent(taskId, eventData);
        } catch (Exception e) {
            log.debug("sync task status failed: taskId={}, err={}", taskId, e.getMessage());
        }
    }

    private void sendEvent(WebSocketSession session, MapRecord<String, Object, Object> record) {
        try {
            Map<String, Object> event = extractEventData(record);
            String taskNo = firstNonBlank(event, "task_no", "task_id");
            if (!StringUtils.hasText(taskNo)) {
                taskNo = sessionTaskMap.get(session.getId());
            }
            enrichOutputReadyEvent(event);
            taskPathlistSyncService.enrichEventForDisplay(taskNo, event);
            event.put("_id", record.getId().getValue());
            String jsonMessage = objectMapper.writeValueAsString(event);
            log.debug("Sending WebSocket event: {}", jsonMessage);
            session.sendMessage(new TextMessage(jsonMessage));
        } catch (IOException e) {
            log.error("send websocket event failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractEventData(MapRecord<String, Object, Object> record) {
        Map<Object, Object> rawValues = record.getValue();

        Object payloadObj = rawValues.get("payload");
        if (payloadObj instanceof String payloadStr && payloadStr.startsWith("{")) {
            try {
                Map<String, Object> parsed = objectMapper.readValue(payloadStr, Map.class);
                Object innerData = parsed.get("data");
                if (innerData instanceof String dataStr && (dataStr.startsWith("{") || dataStr.startsWith("["))) {
                    try {
                        parsed.put("data", objectMapper.readValue(dataStr, Object.class));
                    } catch (Exception ignored) {
                    }
                }
                Object data = parsed.get("data");
                if (data instanceof Map<?, ?> dataMap) {
                    dataMap.forEach((k, v) -> parsed.putIfAbsent(k.toString(), v));
                }
                return parsed;
            } catch (Exception e) {
                log.warn("parse payload json failed, fallback flat event: {}", e.getMessage());
            }
        }

        Map<String, Object> event = new HashMap<>();
        rawValues.forEach((k, v) -> {
            String key = k.toString();
            Object value = v;
            if ("data".equals(key) && v instanceof String strVal) {
                if (strVal.startsWith("{") || strVal.startsWith("[")) {
                    try {
                        value = objectMapper.readValue(strVal, Object.class);
                    } catch (Exception ignored) {
                    }
                }
            }
            event.put(key, value);
        });

        Object data = event.get("data");
        if (data instanceof Map<?, ?> dataMap) {
            dataMap.forEach((k, v) -> event.putIfAbsent(k.toString(), v));
        }
        return event;
    }

    @SuppressWarnings("unchecked")
    private void enrichOutputReadyEvent(Map<String, Object> eventData) {
        try {
            String eventType = (String) eventData.get("event_type");
            if (!"TASK_OUTPUT_READY".equals(eventType)) {
                return;
            }

            Object dataObj = eventData.get("data");
            if (!(dataObj instanceof Map)) {
                return;
            }

            Map<String, Object> data = (Map<String, Object>) dataObj;
            String outputType = (String) data.get("output_type");
            if (!"oss".equals(outputType)) {
                return;
            }

            String ossPath = normalizeOssPath((String) data.get("oss_path"));
            if (!StringUtils.hasText(ossPath)) {
                return;
            }
            String taskNo = firstNonBlank(eventData, "task_no", "task_id");

            taskOutputWorkspaceSyncService.syncTaskOutput(taskNo, data).ifPresent(context -> {
                data.put("workspace_space_type", "WORKSTATION");
                data.put("workspace_workstation_id", context.workstationId());
                data.put("workspace_parent_node_id", context.parentNodeId());
                data.put("workspace_node_id", context.taskNodeId());
            });

            List<String> candidatePrefixes = buildOssCandidatePrefixes(eventData, ossPath);
            String resolvedOssPath = choosePreferredOssPath(candidatePrefixes);
            data.put("oss_path", ossPath);
            data.put("oss_path_resolved", resolvedOssPath);
            data.put("nfs_path", "/data/oss/robot/" + ossPath);
            data.put("artifacts_pending", Boolean.TRUE);

            if (!nfsStorageService.isConfigured()) {
                log.debug("TASK_OUTPUT_READY: NFS not configured, only keep path contract: {}", resolvedOssPath);
                return;
            }

            List<String> objectNames = List.of();
            String matchedPrefix = null;
            for (String prefix : candidatePrefixes) {
                objectNames = nfsStorageService.listObjects(prefix);
                if (!objectNames.isEmpty()) {
                    matchedPrefix = prefix;
                    break;
                }
            }

            if (objectNames.isEmpty()) {
                log.info("TASK_OUTPUT_READY path contract emitted but directory empty: candidates={}, oss_path={}",
                        candidatePrefixes, ossPath);
                return;
            }

            if (matchedPrefix != null && !matchedPrefix.equals(data.get("oss_path_resolved"))) {
                data.put("oss_path_resolved", matchedPrefix);
            }

            List<Map<String, String>> artifacts = new ArrayList<>();
            for (String objectName : objectNames) {
                String fileName = objectName;
                int lastSlash = objectName.lastIndexOf('/');
                if (lastSlash >= 0 && lastSlash < objectName.length() - 1) {
                    fileName = objectName.substring(lastSlash + 1);
                }
                String downloadUrl = nfsStorageService.buildTaskOutputDownloadUrl(objectName);
                Map<String, String> artifact = new LinkedHashMap<>();
                artifact.put("name", fileName);
                artifact.put("download_url", downloadUrl);
                artifacts.add(artifact);
            }

            if (!artifacts.isEmpty()) {
                data.put("artifacts", artifacts);
                data.put("artifacts_pending", Boolean.FALSE);
                log.info("TASK_OUTPUT_READY enriched: oss_path={}, resolved={}, artifacts={}",
                        ossPath, data.get("oss_path_resolved"), artifacts.size());
            }
        } catch (Exception e) {
            log.warn("enrich TASK_OUTPUT_READY failed (non-blocking): {}", e.getMessage());
        }
    }

    private List<String> buildOssCandidatePrefixes(Map<String, Object> eventData, String rawOssPath) {
        String ossPath = normalizeOssPath(rawOssPath);
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(ossPath);

        if (!ossPath.startsWith("system/")) {
            String workstationId = resolveWorkstationId(eventData);
            if (StringUtils.hasText(workstationId)) {
                candidates.add("system/" + workstationId + "/" + ossPath);
            }
        }
        return new ArrayList<>(candidates);
    }

    private String normalizeOssPath(String rawOssPath) {
        String normalized = rawOssPath == null ? "" : rawOssPath.trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private String choosePreferredOssPath(List<String> candidates) {
        for (String candidate : candidates) {
            if (candidate.startsWith("system/")) {
                return candidate;
            }
        }
        return candidates.isEmpty() ? "" : candidates.get(0);
    }

    private String resolveWorkstationId(Map<String, Object> eventData) {
        String taskNo = firstNonBlank(eventData, "task_no", "task_id");
        if (!StringUtils.hasText(taskNo)) {
            return null;
        }

        String cached = taskWorkstationCache.get(taskNo);
        if (StringUtils.hasText(cached)) {
            return cached;
        }

        try {
            Long roleId = taskService.getTaskByNo(taskNo).getRoleId();
            if (roleId == null) {
                return null;
            }
            String workstationId = String.valueOf(roleId);
            taskWorkstationCache.put(taskNo, workstationId);
            return workstationId;
        } catch (Exception e) {
            log.debug("resolve workstationId failed: taskNo={}, err={}", taskNo, e.getMessage());
            return null;
        }
    }

    private String firstNonBlank(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            Object value = source.get(key);
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value).trim();
            if (!text.isEmpty()) {
                return text;
            }
        }
        return null;
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String taskId = sessionTaskMap.remove(session.getId());
        sessions.remove(session.getId());
        log.info("WebSocket closed: {}, taskId: {}", session.getId(), taskId);
    }
}
