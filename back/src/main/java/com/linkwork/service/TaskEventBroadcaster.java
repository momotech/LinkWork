package com.linkwork.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class TaskEventBroadcaster {

    @FunctionalInterface
    public interface TaskEventListener {
        void onEvent(String taskNo, MapRecord<String, Object, Object> record);
    }

    private final Map<String, TaskEventListener> listeners = new ConcurrentHashMap<>();

    public String register(TaskEventListener listener) {
        String listenerId = UUID.randomUUID().toString();
        listeners.put(listenerId, listener);
        return listenerId;
    }

    public void unregister(String listenerId) {
        if (listenerId == null || listenerId.isBlank()) return;
        listeners.remove(listenerId);
    }

    public void broadcast(String taskNo, MapRecord<String, Object, Object> record) {
        if (taskNo == null || taskNo.isBlank() || record == null || listeners.isEmpty()) return;
        listeners.forEach((id, listener) -> {
            try {
                listener.onEvent(taskNo, record);
            } catch (Exception e) {
                log.warn("TaskEventBroadcaster listener failed: id={}, taskNo={}, err={}", id, taskNo, e.getMessage());
            }
        });
    }
}
