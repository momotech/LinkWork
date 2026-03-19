package com.linkwork.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkwork.config.DispatchConfig;
import com.linkwork.service.TaskEventBroadcaster;
import com.linkwork.service.TaskV1Service;
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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class TaskWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final StringRedisTemplate redisTemplate;
    private final TaskV1Service taskService;
    private final DispatchConfig dispatchConfig;
    private final TaskEventBroadcaster taskEventBroadcaster;

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> sessionTaskMap = new ConcurrentHashMap<>();

    private String broadcastListenerId;

    public TaskWebSocketHandler(StringRedisTemplate redisTemplate,
                                TaskV1Service taskService,
                                DispatchConfig dispatchConfig,
                                TaskEventBroadcaster taskEventBroadcaster) {
        this.redisTemplate = redisTemplate;
        this.taskService = taskService;
        this.dispatchConfig = dispatchConfig;
        this.taskEventBroadcaster = taskEventBroadcaster;
    }

    @PostConstruct
    public void registerBroadcaster() {
        broadcastListenerId = taskEventBroadcaster.register(this::broadcastToTask);
    }

    @PreDestroy
    public void unregisterBroadcaster() {
        taskEventBroadcaster.unregister(broadcastListenerId);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.put(session.getId(), session);
        String taskId = extractTaskId(session);
        log.info("WebSocket connected: {}, taskId: {}", session.getId(), taskId);
        if (taskId != null) bindTask(session, taskId);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Map<String, Object> req = objectMapper.readValue(message.getPayload(), Map.class);
        String action = (String) req.get("action");
        String taskId = (String) req.get("taskId");
        if ("bind".equals(action) && taskId != null) bindTask(session, taskId);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessionTaskMap.remove(session.getId());
        sessions.remove(session.getId());
    }

    private void bindTask(WebSocketSession session, String taskId) {
        sessionTaskMap.put(session.getId(), taskId);
        pushHistoryEvents(session, taskId);
    }

    private void pushHistoryEvents(WebSocketSession session, String taskId) {
        try {
            List<String> keys = buildStreamKeys(taskId);
            Set<String> sentIds = new HashSet<>();
            for (String key : keys) {
                try {
                    List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(StreamOffset.fromStart(key));
                    if (records == null) continue;
                    for (MapRecord<String, Object, Object> r : records) {
                        if (sentIds.add(r.getId().getValue())) sendEvent(session, r);
                    }
                } catch (Exception e) {
                    log.debug("history stream read skipped: key={}", key);
                }
            }
        } catch (Exception e) {
            log.error("push history events failed: taskId={}", taskId, e);
        }
    }

    private void broadcastToTask(String taskId, MapRecord<String, Object, Object> record) {
        sessionTaskMap.forEach((sid, tid) -> {
            if (!taskId.equals(tid)) return;
            WebSocketSession s = sessions.get(sid);
            if (s != null && s.isOpen()) sendEvent(s, record);
        });
    }

    @SuppressWarnings("unchecked")
    private void sendEvent(WebSocketSession session, MapRecord<String, Object, Object> record) {
        try {
            Map<String, Object> event = new HashMap<>();
            record.getValue().forEach((k, v) -> {
                String key = k.toString();
                Object val = v;
                if ("data".equals(key) && v instanceof String s && (s.startsWith("{") || s.startsWith("["))) {
                    try { val = objectMapper.readValue(s, Object.class); } catch (Exception ignored) {}
                }
                event.put(key, val);
            });
            Object data = event.get("data");
            if (data instanceof Map<?, ?> dm) {
                dm.forEach((dk, dv) -> event.putIfAbsent(dk.toString(), dv));
            }
            event.put("_id", record.getId().getValue());
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(event)));
        } catch (IOException e) {
            log.error("send ws event failed: {}", e.getMessage());
        }
    }

    private List<String> buildStreamKeys(String taskId) {
        Long wsId = resolveWorkstationId(taskId);
        return List.of(
                dispatchConfig.getLogStreamKey(wsId, taskId),
                "stream:task:" + taskId,
                "stream:task:" + taskId + ":events",
                "stream:build:" + taskId);
    }

    private Long resolveWorkstationId(String taskId) {
        try { return taskService.getTaskByNo(taskId).getWorkstationId(); }
        catch (Exception e) { return null; }
    }

    private String extractTaskId(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri != null && uri.getQuery() != null) {
            for (String param : uri.getQuery().split("&")) {
                String[] kv = param.split("=");
                if (kv.length == 2 && "taskId".equals(kv[0])) return kv[1];
            }
        }
        return null;
    }
}
