package com.linkwork.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkwork.config.DispatchConfig;
import com.linkwork.model.entity.LinkworkTask;
import com.linkwork.model.enums.TaskStatus;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Persistent task-event consumer:
 * 1) consume Redis stream events
 * 2) sync task status via TaskStatusSyncService
 * 3) broadcast to websocket listeners
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskEventConsumerService {

    private static final String CONSUMER_GROUP = "backend-core";
    private static final int SCAN_PAGE_SIZE = 200;
    private static final long LISTENER_RETAIN_MS = 300_000L;

    private final StringRedisTemplate redisTemplate;
    private final TaskV1Service taskService;
    private final DispatchConfig dispatchConfig;
    private final TaskStatusSyncService taskStatusSyncService;
    private final TaskEventBroadcaster taskEventBroadcaster;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService workerPool = Executors.newCachedThreadPool();
    private final Map<String, ListenerState> listeners = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        discoverAndMaintainListeners();
    }

    @PreDestroy
    public void shutdown() {
        listeners.values().forEach(listener -> listener.future().cancel(true));
        workerPool.shutdownNow();
    }

    @Scheduled(fixedDelayString = "${linkwork.task-event-consumer.scan-interval-ms:5000}")
    public void discoverAndMaintainListeners() {
        try {
            long now = System.currentTimeMillis();
            Set<String> activeTasks = new HashSet<>();

            for (TaskStatus status : List.of(TaskStatus.PENDING, TaskStatus.RUNNING, TaskStatus.PENDING_AUTH)) {
                long current = 1;
                while (true) {
                    Page<LinkworkTask> page = taskService.listTasks(null, status.name(), (int) current, SCAN_PAGE_SIZE, null);
                    List<LinkworkTask> records = page.getRecords();
                    if (records == null || records.isEmpty()) {
                        break;
                    }
                    for (LinkworkTask task : records) {
                        String taskNo = task.getTaskNo();
                        if (taskNo == null || taskNo.isBlank()) {
                            continue;
                        }
                        activeTasks.add(taskNo);
                        startListenerIfAbsent(task);
                        ListenerState existed = listeners.get(taskNo);
                        if (existed != null) {
                            existed.touch(now);
                        }
                    }
                    if (current >= page.getPages()) {
                        break;
                    }
                    current++;
                }
            }

            for (TaskStatus terminalStatus : List.of(TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.ABORTED)) {
                Page<LinkworkTask> page = taskService.listTasks(null, terminalStatus.name(), 1, SCAN_PAGE_SIZE, null);
                List<LinkworkTask> records = page.getRecords();
                if (records == null || records.isEmpty()) {
                    continue;
                }
                for (LinkworkTask task : records) {
                    if (!isRecentlyUpdated(task, now, LISTENER_RETAIN_MS)) {
                        continue;
                    }
                    startListenerIfAbsent(task);
                    ListenerState existed = listeners.get(task.getTaskNo());
                    if (existed != null) {
                        existed.touch(now);
                    }
                }
            }

            listeners.forEach((taskNo, state) -> {
                if (activeTasks.contains(taskNo)) {
                    return;
                }
                if (now - state.lastActiveAt() < LISTENER_RETAIN_MS) {
                    return;
                }
                try {
                    LinkworkTask task = taskService.getTaskByNo(taskNo);
                    if (task.getStatus() == TaskStatus.PENDING
                            || task.getStatus() == TaskStatus.RUNNING
                            || task.getStatus() == TaskStatus.PENDING_AUTH) {
                        state.touch(now);
                        return;
                    }
                } catch (Exception e) {
                    log.debug("listener cleanup skip taskNo={} because task lookup failed: {}", taskNo, e.getMessage());
                }
                state.future().cancel(true);
                listeners.remove(taskNo);
                log.info("task event listener removed: taskNo={}", taskNo);
            });
        } catch (Exception e) {
            log.error("discover task listeners failed: {}", e.getMessage(), e);
        }
    }

    private boolean isRecentlyUpdated(LinkworkTask task, long nowMillis, long thresholdMillis) {
        LocalDateTime updatedAt = task.getUpdatedAt();
        if (updatedAt == null) {
            return false;
        }
        long updatedMillis = updatedAt.toInstant(ZoneOffset.UTC).toEpochMilli();
        return nowMillis - updatedMillis <= thresholdMillis;
    }

    private void startListenerIfAbsent(LinkworkTask task) {
        listeners.computeIfAbsent(task.getTaskNo(), taskNo -> {
            List<String> streamKeys = buildStreamKeys(task);
            String consumerName = "core-" + taskNo;
            for (String streamKey : streamKeys) {
                try {
                    redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.from("0"), CONSUMER_GROUP);
                } catch (Exception ignored) {
                    // stream/group may already exist
                }
            }
            Future<?> future = workerPool.submit(() -> consumeLoop(taskNo, streamKeys, consumerName));
            log.info("task event listener started: taskNo={}, streamKeys={}", taskNo, streamKeys);
            return new ListenerState(future, System.currentTimeMillis());
        });
    }

    private List<String> buildStreamKeys(LinkworkTask task) {
        List<String> keys = new ArrayList<>();
        keys.add(dispatchConfig.getLogStreamKey(task.getWorkstationId(), task.getTaskNo()));
        keys.add("stream:task:" + task.getTaskNo());
        keys.add("stream:task:" + task.getTaskNo() + ":events");
        keys.add("stream:build:" + task.getTaskNo());
        return keys;
    }

    private void consumeLoop(String taskNo, List<String> streamKeys, String consumerName) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                for (String streamKey : streamKeys) {
                    List<MapRecord<String, Object, Object>> records;
                    try {
                        records = redisTemplate.opsForStream().read(
                                Consumer.from(CONSUMER_GROUP, consumerName),
                                StreamReadOptions.empty().count(20).block(Duration.ofMillis(500)),
                                StreamOffset.create(streamKey, ReadOffset.lastConsumed())
                        );
                    } catch (Exception e) {
                        continue;
                    }
                    if (records == null || records.isEmpty()) {
                        continue;
                    }
                    for (MapRecord<String, Object, Object> record : records) {
                        processRecord(taskNo, record);
                        touchListener(taskNo);
                        try {
                            redisTemplate.opsForStream().acknowledge(streamKey, CONSUMER_GROUP, record.getId());
                        } catch (Exception e) {
                            log.debug("ack stream record failed: streamKey={}, recordId={}, err={}",
                                    streamKey, record.getId(), e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                if (!Thread.currentThread().isInterrupted()) {
                    log.warn("task event consume loop error: taskNo={}, err={}", taskNo, e.getMessage(), e);
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
    }

    private void touchListener(String taskNo) {
        ListenerState state = listeners.get(taskNo);
        if (state != null) {
            state.touch(System.currentTimeMillis());
        }
    }

    private void processRecord(String taskNo, MapRecord<String, Object, Object> record) {
        Map<String, Object> eventData = extractEventData(record);
        taskStatusSyncService.onEvent(taskNo, eventData);
        taskEventBroadcaster.broadcast(taskNo, record);
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
                Object dataObj = parsed.get("data");
                if (dataObj instanceof Map<?, ?> dataMap) {
                    dataMap.forEach((k, v) -> parsed.putIfAbsent(String.valueOf(k), v));
                }
                return parsed;
            } catch (Exception e) {
                log.debug("parse payload failed, fallback flat event: {}", e.getMessage());
            }
        }

        Map<String, Object> event = new HashMap<>();
        rawValues.forEach((k, v) -> event.put(String.valueOf(k), v));

        Object dataObj = event.get("data");
        if (dataObj instanceof String dataStr && (dataStr.startsWith("{") || dataStr.startsWith("["))) {
            try {
                event.put("data", objectMapper.readValue(dataStr, Object.class));
            } catch (Exception ignored) {
            }
        }
        Object dataMapObj = event.get("data");
        if (dataMapObj instanceof Map<?, ?> dataMap) {
            dataMap.forEach((k, v) -> event.putIfAbsent(String.valueOf(k), v));
        }
        return event;
    }

    private static final class ListenerState {
        private final Future<?> future;
        private volatile long lastActiveAt;

        private ListenerState(Future<?> future, long lastActiveAt) {
            this.future = future;
            this.lastActiveAt = lastActiveAt;
        }

        private Future<?> future() {
            return future;
        }

        private long lastActiveAt() {
            return lastActiveAt;
        }

        private void touch(long ts) {
            this.lastActiveAt = ts;
        }
    }
}
