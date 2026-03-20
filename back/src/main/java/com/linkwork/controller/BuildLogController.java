package com.linkwork.controller;

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

/**
 * 构建日志 SSE 控制器
 * 直接从 build.sh 执行输出推送日志，不依赖 Redis Stream
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/build-logs")
@RequiredArgsConstructor
public class BuildLogController {
    
    private final BuildLogBuffer logBuffer;
    private final BuildRecordService buildRecordService;

    /**
     * 兼容旧查询入口：按 roleId 获取最近一次构建日志
     */
    @GetMapping
    public Map<String, Object> getLogsByQuery(
            @RequestParam(required = false) String buildId,
            @RequestParam(required = false) Long roleId,
            @RequestParam(defaultValue = "0") int afterIndex) {

        String targetBuildId = buildId;
        if ((targetBuildId == null || targetBuildId.isBlank()) && roleId != null) {
            var latest = buildRecordService.getLatestByRoleId(roleId);
            if (latest == null || latest.getBuildNo() == null || latest.getBuildNo().isBlank()) {
                return Map.of(
                        "buildId", "",
                        "logs", List.of(),
                        "totalCount", 0,
                        "completed", true,
                        "success", false
                );
            }
            targetBuildId = latest.getBuildNo();
        }

        if (targetBuildId == null || targetBuildId.isBlank()) {
            throw new IllegalArgumentException("buildId 或 roleId 至少传一个");
        }
        return getLogs(targetBuildId, afterIndex);
    }
    
    /**
     * SSE 端点：实时接收构建日志
     * 
     * @param buildId 构建 ID
     * @return SSE 事件流
     */
    @GetMapping(value = "/{buildId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamLogs(@PathVariable String buildId) {
        log.info("SSE connection opened for buildId: {}", buildId);
        
        // 10 分钟超时
        SseEmitter emitter = new SseEmitter(TimeUnit.MINUTES.toMillis(10));
        
        // 先推送历史日志
        List<BuildLogBuffer.LogEntry> history = logBuffer.getHistory(buildId);
        for (BuildLogBuffer.LogEntry entry : history) {
            try {
                emitter.send(SseEmitter.event()
                    .name("log")
                    .data(Map.of(
                        "timestamp", entry.timestamp(),
                        "level", entry.level(),
                        "message", entry.message()
                    )));
            } catch (IOException e) {
                log.debug("Failed to send history log: {}", e.getMessage());
            }
        }
        
        // 如果构建已完成，发送完成事件并关闭
        if (logBuffer.isCompleted(buildId)) {
            try {
                Boolean success = logBuffer.getCompletionStatus(buildId);
                emitter.send(SseEmitter.event().name("complete").data(Map.of(
                    "success", success != null ? success : false,
                    "message", success != null && success ? "构建成功" : "构建失败"
                )));
                emitter.complete();
            } catch (IOException e) {
                log.debug("Failed to send complete event: {}", e.getMessage());
            }
            return emitter;
        }
        
        // 订阅新日志
        java.util.function.Consumer<BuildLogBuffer.LogEntry> subscriber = entry -> {
            try {
                emitter.send(SseEmitter.event()
                    .name("log")
                    .data(Map.of(
                        "timestamp", entry.timestamp(),
                        "level", entry.level(),
                        "message", entry.message()
                    )));
                
                // 检查是否完成
                if (logBuffer.isCompleted(buildId)) {
                    Boolean success = logBuffer.getCompletionStatus(buildId);
                    emitter.send(SseEmitter.event().name("complete").data(Map.of(
                        "success", success != null ? success : false,
                        "message", success != null && success ? "构建成功" : "构建失败"
                    )));
                    emitter.complete();
                }
            } catch (IOException e) {
                log.debug("Failed to send log via SSE: {}", e.getMessage());
            }
        };
        
        logBuffer.subscribe(buildId, subscriber);
        
        // 连接关闭时取消订阅
        emitter.onCompletion(() -> {
            logBuffer.unsubscribe(buildId, subscriber);
            log.debug("SSE connection completed for buildId: {}", buildId);
        });
        
        emitter.onTimeout(() -> {
            logBuffer.unsubscribe(buildId, subscriber);
            log.debug("SSE connection timeout for buildId: {}", buildId);
        });
        
        emitter.onError(e -> {
            logBuffer.unsubscribe(buildId, subscriber);
            log.debug("SSE connection error for buildId: {}", buildId);
        });
        
        return emitter;
    }
    
    /**
     * 轮询接口：获取历史日志（备用）
     */
    @GetMapping("/{buildId}")
    public Map<String, Object> getLogs(
            @PathVariable String buildId,
            @RequestParam(defaultValue = "0") int afterIndex) {
        
        List<BuildLogBuffer.LogEntry> logs = logBuffer.getLogsAfter(buildId, afterIndex);
        List<BuildLogBuffer.LogEntry> allLogs = logBuffer.getHistory(buildId);
        boolean completed = logBuffer.isCompleted(buildId);
        Boolean success = logBuffer.getCompletionStatus(buildId);
        
        return Map.of(
            "buildId", buildId,
            "logs", logs.stream().map(e -> Map.of(
                "timestamp", e.timestamp(),
                "level", e.level(),
                "message", e.message()
            )).toList(),
            "totalCount", allLogs.size(),
            "completed", completed,
            "success", success != null ? success : false
        );
    }
}
