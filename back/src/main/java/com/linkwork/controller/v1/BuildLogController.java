package com.linkwork.controller.v1;

import com.linkwork.service.BuildLogBuffer;
import com.linkwork.service.BuildRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Slf4j
@RestController
@RequestMapping("/api/v1/build-logs")
@RequiredArgsConstructor
public class BuildLogController {

    private final BuildLogBuffer logBuffer;
    private final BuildRecordService buildRecordService;

    @GetMapping
    public Map<String, Object> getLogsByQuery(
            @RequestParam(required = false) String buildId,
            @RequestParam(required = false) Long roleId,
            @RequestParam(defaultValue = "0") int afterIndex) {
        String targetBuildId = buildId;
        if ((targetBuildId == null || targetBuildId.isBlank()) && roleId != null) {
            var latest = buildRecordService.getLatestByRoleId(roleId);
            if (latest == null || latest.getBuildNo() == null || latest.getBuildNo().isBlank()) {
                return Map.of("buildId", "", "logs", List.of(),
                        "totalCount", 0, "completed", true, "success", false);
            }
            targetBuildId = latest.getBuildNo();
        }
        if (targetBuildId == null || targetBuildId.isBlank()) {
            throw new IllegalArgumentException("buildId or roleId required");
        }
        return getLogs(targetBuildId, afterIndex);
    }

    @GetMapping(value = "/{buildId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamLogs(@PathVariable String buildId) {
        log.info("SSE connection established for buildId: {}", buildId);
        SseEmitter emitter = new SseEmitter(TimeUnit.MINUTES.toMillis(10));

        List<BuildLogBuffer.LogEntry> history = logBuffer.getHistory(buildId);
        for (BuildLogBuffer.LogEntry entry : history) {
            try {
                emitter.send(SseEmitter.event().name("log").data(Map.of(
                        "timestamp", entry.timestamp(), "level", entry.level(),
                        "message", entry.message())));
            } catch (IOException e) {
                log.debug("Failed to send history log: {}", e.getMessage());
            }
        }

        if (logBuffer.isCompleted(buildId)) {
            try {
                Boolean success = logBuffer.getCompletionStatus(buildId);
                emitter.send(SseEmitter.event().name("complete").data(Map.of(
                        "success", success != null ? success : false,
                        "message", success != null && success ? "Build succeeded" : "Build failed")));
                emitter.complete();
            } catch (IOException e) {
                log.debug("Failed to send complete event: {}", e.getMessage());
            }
            return emitter;
        }

        Consumer<BuildLogBuffer.LogEntry> subscriber = entry -> {
            try {
                emitter.send(SseEmitter.event().name("log").data(Map.of(
                        "timestamp", entry.timestamp(), "level", entry.level(),
                        "message", entry.message())));
                if (logBuffer.isCompleted(buildId)) {
                    Boolean success = logBuffer.getCompletionStatus(buildId);
                    emitter.send(SseEmitter.event().name("complete").data(Map.of(
                            "success", success != null ? success : false,
                            "message", success != null && success ? "Build succeeded" : "Build failed")));
                    emitter.complete();
                }
            } catch (IOException e) {
                log.debug("Failed to send log via SSE: {}", e.getMessage());
            }
        };

        logBuffer.subscribe(buildId, subscriber);

        emitter.onCompletion(() -> logBuffer.unsubscribe(buildId, subscriber));
        emitter.onTimeout(() -> logBuffer.unsubscribe(buildId, subscriber));
        emitter.onError(e -> logBuffer.unsubscribe(buildId, subscriber));

        return emitter;
    }

    @GetMapping("/{buildId}")
    public Map<String, Object> getLogs(@PathVariable String buildId,
                                        @RequestParam(defaultValue = "0") int afterIndex) {
        List<BuildLogBuffer.LogEntry> logs = logBuffer.getLogsAfter(buildId, afterIndex);
        List<BuildLogBuffer.LogEntry> allLogs = logBuffer.getHistory(buildId);
        boolean completed = logBuffer.isCompleted(buildId);
        Boolean success = logBuffer.getCompletionStatus(buildId);
        return Map.of(
                "buildId", buildId,
                "logs", logs.stream().map(e -> Map.of(
                        "timestamp", e.timestamp(), "level", e.level(),
                        "message", e.message())).toList(),
                "totalCount", allLogs.size(),
                "completed", completed,
                "success", success != null ? success : false);
    }
}
