package com.linkwork.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内任务事件广播器。
 *
 * 事件消费和 WebSocket 推送解耦：
 * - 后端常驻消费者只负责消费并广播
 * - WebSocket handler 只负责订阅并推送给在线客户端
 */
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
        if (listenerId == null || listenerId.isBlank()) {
            return;
        }
        listeners.remove(listenerId);
    }

    public void broadcast(String taskNo, MapRecord<String, Object, Object> record) {
        if (taskNo == null || taskNo.isBlank() || record == null || listeners.isEmpty()) {
            return;
        }
        listeners.forEach((listenerId, listener) -> {
            try {
                listener.onEvent(taskNo, record);
            } catch (Exception e) {
                log.warn("TaskEventBroadcaster listener failed: listenerId={}, taskNo={}, err={}",
                        listenerId, taskNo, e.getMessage(), e);
            }
        });
    }
}
