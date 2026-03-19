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
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskEventConsumerService {

    private static final String CONSUMER_GROUP = "backend-core";
    private static final int SCAN_PAGE_SIZE = 200;

    private final StringRedisTemplate redisTemplate;
    private final TaskV1Service taskService;
    private final DispatchConfig dispatchConfig;
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
        listeners.values().forEach(s -> s.future.cancel(true));
        workerPool.shutdownNow();
    }

    @Scheduled(fixedDelayString = "${linkwork.task-event-consumer.scan-interval-ms:5000}")
    public void discoverAndMaintainListeners() {
        try {
            long now = System.currentTimeMillis();
            Set<String> active = new HashSet<>();

            for (TaskStatus status : List.of(TaskStatus.PENDING, TaskStatus.RUNNING, TaskStatus.PENDING_AUTH)) {
                long current = 1;
                while (true) {
                    Page<LinkworkTask> page = taskService.listTasks(null, status.name(), (int) current, SCAN_PAGE_SIZE, null);
                    List<LinkworkTask> records = page.getRecords();
                    if (records == null || records.isEmpty()) break;
                    for (LinkworkTask t : records) {
                        if (t.getTaskNo() == null) continue;
                        active.add(t.getTaskNo());
                        startListenerIfAbsent(t);
                    }
                    if (current >= page.getPages()) break;
                    current++;
                }
            }

            listeners.entrySet().removeIf(entry -> {
                if (active.contains(entry.getKey())) return false;
                if (now - entry.getValue().lastActive < 300_000L) return false;
                entry.getValue().future.cancel(true);
                return true;
            });
        } catch (Exception e) {
            log.error("discover task listeners failed: {}", e.getMessage());
        }
    }

    private void startListenerIfAbsent(LinkworkTask task) {
        listeners.computeIfAbsent(task.getTaskNo(), taskNo -> {
            List<String> keys = buildStreamKeys(task);
            String consumer = "core-" + taskNo;
            for (String key : keys) {
                try { redisTemplate.opsForStream().createGroup(key, ReadOffset.from("0"), CONSUMER_GROUP); }
                catch (Exception ignored) {}
            }
            Future<?> f = workerPool.submit(() -> consumeLoop(taskNo, keys, consumer));
            return new ListenerState(f, System.currentTimeMillis());
        });
    }

    private List<String> buildStreamKeys(LinkworkTask task) {
        return List.of(
                dispatchConfig.getLogStreamKey(task.getWorkstationId(), task.getTaskNo()),
                "stream:task:" + task.getTaskNo(),
                "stream:task:" + task.getTaskNo() + ":events",
                "stream:build:" + task.getTaskNo());
    }

    private void consumeLoop(String taskNo, List<String> keys, String consumer) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                for (String key : keys) {
                    List<MapRecord<String, Object, Object>> records;
                    try {
                        records = redisTemplate.opsForStream().read(
                                Consumer.from(CONSUMER_GROUP, consumer),
                                StreamReadOptions.empty().count(20).block(Duration.ofMillis(500)),
                                StreamOffset.create(key, ReadOffset.lastConsumed()));
                    } catch (Exception e) { continue; }
                    if (records == null || records.isEmpty()) continue;
                    for (MapRecord<String, Object, Object> r : records) {
                        taskEventBroadcaster.broadcast(taskNo, r);
                        try { redisTemplate.opsForStream().acknowledge(key, CONSUMER_GROUP, r.getId()); }
                        catch (Exception ignored) {}
                    }
                }
            } catch (Exception e) {
                if (!Thread.currentThread().isInterrupted()) {
                    try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
            }
        }
    }

    private static final class ListenerState {
        final Future<?> future;
        volatile long lastActive;
        ListenerState(Future<?> f, long t) { future = f; lastActive = t; }
    }
}
