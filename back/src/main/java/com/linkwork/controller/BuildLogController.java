package com.linkwork.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linkwork.config.DispatchConfig;
import com.linkwork.mapper.LinkworkTaskMapper;
import com.linkwork.model.entity.LinkworkTask;
import com.linkwork.model.enums.TaskStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@RestController
@RequestMapping("/api/v1/build-logs")
@RequiredArgsConstructor
public class BuildLogController {

    private final StringRedisTemplate redisTemplate;
    private final LinkworkTaskMapper taskMapper;
    private final DispatchConfig dispatchConfig;

    @GetMapping
    public Map<String, Object> getLogsByQuery(
            @RequestParam(required = false) String buildId,
            @RequestParam(required = false) Long roleId,
            @RequestParam(defaultValue = "0") int afterIndex) {

        String targetBuildId = buildId;
        if (!StringUtils.hasText(targetBuildId) && roleId != null) {
            LinkworkTask latest = findLatestTaskByRoleId(roleId);
            if (latest == null || !StringUtils.hasText(latest.getTaskNo())) {
                return Map.of(
                        "buildId", "",
                        "logs", List.of(),
                        "totalCount", 0,
                        "completed", true,
                        "success", false
                );
            }
            targetBuildId = latest.getTaskNo();
        }

        if (!StringUtils.hasText(targetBuildId)) {
            throw new IllegalArgumentException("buildId 或 roleId 至少传一个");
        }
        return getLogs(targetBuildId, afterIndex);
    }

    @GetMapping(value = "/{buildId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamLogs(@PathVariable String buildId) {
        SseEmitter emitter = new SseEmitter(TimeUnit.MINUTES.toMillis(10));
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "build-log-stream-" + buildId);
            t.setDaemon(true);
            return t;
        });

        final int[] cursor = new int[]{0};
        try {
            Map<String, Object> init = getLogs(buildId, 0);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> logs = (List<Map<String, Object>>) init.get("logs");
            for (Map<String, Object> logItem : logs) {
                emitter.send(SseEmitter.event().name("log").data(logItem));
            }
            cursor[0] = logs.size();
            if (Boolean.TRUE.equals(init.get("completed"))) {
                sendCompleteAndClose(emitter, Boolean.TRUE.equals(init.get("success")));
                scheduler.shutdownNow();
                return emitter;
            }
        } catch (Exception ex) {
            log.debug("Init stream logs failed: buildId={}, err={}", buildId, ex.getMessage());
        }

        scheduler.scheduleAtFixedRate(() -> {
            try {
                Map<String, Object> batch = getLogs(buildId, cursor[0]);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> logs = (List<Map<String, Object>>) batch.get("logs");
                for (Map<String, Object> logItem : logs) {
                    emitter.send(SseEmitter.event().name("log").data(logItem));
                }
                cursor[0] += logs.size();
                if (Boolean.TRUE.equals(batch.get("completed"))) {
                    sendCompleteAndClose(emitter, Boolean.TRUE.equals(batch.get("success")));
                    scheduler.shutdownNow();
                }
            } catch (Exception ex) {
                log.debug("Polling stream logs failed: buildId={}, err={}", buildId, ex.getMessage());
            }
        }, 1, 1, TimeUnit.SECONDS);

        emitter.onCompletion(scheduler::shutdownNow);
        emitter.onTimeout(scheduler::shutdownNow);
        emitter.onError(ex -> scheduler.shutdownNow());
        return emitter;
    }

    @GetMapping("/{buildId}")
    public Map<String, Object> getLogs(
            @PathVariable String buildId,
            @RequestParam(defaultValue = "0") int afterIndex) {
        List<LogEntry> allLogs = readAllLogs(buildId);
        int from = Math.max(0, afterIndex);
        List<LogEntry> newLogs = from >= allLogs.size() ? List.of() : allLogs.subList(from, allLogs.size());

        Completion completion = resolveCompletion(buildId);
        return Map.of(
                "buildId", buildId,
                "logs", newLogs.stream().map(LogEntry::toMap).toList(),
                "totalCount", allLogs.size(),
                "completed", completion.completed(),
                "success", completion.success()
        );
    }

    private void sendCompleteAndClose(SseEmitter emitter, boolean success) {
        try {
            emitter.send(SseEmitter.event().name("complete").data(Map.of(
                    "success", success,
                    "message", success ? "构建成功" : "构建失败")));
        } catch (IOException ignored) {
        } finally {
            emitter.complete();
        }
    }

    private List<LogEntry> readAllLogs(String buildId) {
        List<LogEntry> logs = new ArrayList<>();
        for (String key : resolveStreamKeys(buildId)) {
            try {
                List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(StreamOffset.fromStart(key));
                if (records == null || records.isEmpty()) continue;
                for (MapRecord<String, Object, Object> record : records) {
                    logs.add(toLogEntry(record));
                }
            } catch (Exception ex) {
                log.debug("Read build log stream skipped: key={}, err={}", key, ex.getMessage());
            }
        }
        return logs;
    }

    private List<String> resolveStreamKeys(String buildId) {
        Set<String> keys = new LinkedHashSet<>();
        LinkworkTask task = findTaskByTaskNo(buildId);
        if (task != null) {
            keys.add(dispatchConfig.getLogStreamKey(task.getWorkstationId(), task.getTaskNo()));
        }
        keys.add("stream:task:" + buildId);
        keys.add("stream:task:" + buildId + ":events");
        keys.add("stream:build:" + buildId);
        try {
            Set<String> matched = redisTemplate.keys("logs:*:" + buildId);
            if (matched != null) keys.addAll(matched);
        } catch (Exception ignored) {
        }
        return new ArrayList<>(keys);
    }

    private Completion resolveCompletion(String buildId) {
        LinkworkTask task = findTaskByTaskNo(buildId);
        if (task == null || task.getStatus() == null) {
            return new Completion(false, false);
        }
        TaskStatus status = task.getStatus();
        if (status == TaskStatus.COMPLETED) return new Completion(true, true);
        if (status == TaskStatus.FAILED || status == TaskStatus.ABORTED) return new Completion(true, false);
        return new Completion(false, false);
    }

    private LinkworkTask findTaskByTaskNo(String taskNo) {
        if (!StringUtils.hasText(taskNo)) return null;
        LambdaQueryWrapper<LinkworkTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LinkworkTask::getTaskNo, taskNo);
        return taskMapper.selectOne(wrapper);
    }

    private LinkworkTask findLatestTaskByRoleId(Long roleId) {
        if (roleId == null) return null;
        LambdaQueryWrapper<LinkworkTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LinkworkTask::getWorkstationId, roleId)
                .orderByDesc(LinkworkTask::getCreatedAt)
                .last("limit 1");
        return taskMapper.selectOne(wrapper);
    }

    private LogEntry toLogEntry(MapRecord<String, Object, Object> record) {
        Object timestamp = record.getValue().get("timestamp");
        Object eventType = record.getValue().get("event_type");
        Object rawData = record.getValue().get("data");
        String message = Objects.toString(record.getValue().get("message"), null);
        if (!StringUtils.hasText(message)) {
            if (rawData != null && StringUtils.hasText(rawData.toString())) {
                message = eventType == null ? rawData.toString() : eventType + " " + rawData;
            } else if (eventType != null) {
                message = eventType.toString();
            } else {
                message = record.getId().getValue();
            }
        }
        String level = "INFO";
        String lower = message.toLowerCase();
        if (lower.contains("fail") || lower.contains("error")) {
            level = "ERROR";
        }
        String ts = timestamp == null ? LocalDateTime.now().toString() : timestamp.toString();
        return new LogEntry(ts, level, message);
    }

    private record Completion(boolean completed, boolean success) {}

    private record LogEntry(String timestamp, String level, String message) {
        Map<String, Object> toMap() {
            return Map.of(
                    "timestamp", StringUtils.hasText(timestamp) ? timestamp : Instant.now().toString(),
                    "level", StringUtils.hasText(level) ? level : "INFO",
                    "message", StringUtils.hasText(message) ? message : "");
        }
    }
}
