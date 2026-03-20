package com.linkwork.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 构建日志缓冲区
 * 用于存储构建过程中的日志，支持 SSE 推送
 */
@Slf4j
@Component
public class BuildLogBuffer {
    
    /**
     * 日志条目
     */
    public record LogEntry(long timestamp, String level, String message) {}
    
    /**
     * 每个 buildId 的日志缓冲区
     */
    private final Map<String, CopyOnWriteArrayList<LogEntry>> buffers = new ConcurrentHashMap<>();
    
    /**
     * 每个 buildId 的订阅者（SSE 推送用）
     */
    private final Map<String, CopyOnWriteArrayList<Consumer<LogEntry>>> subscribers = new ConcurrentHashMap<>();
    
    /**
     * 构建是否完成的标记
     */
    private final Map<String, Boolean> completed = new ConcurrentHashMap<>();
    
    /**
     * 构建完成状态（success = true, failure = false）
     */
    private final Map<String, Boolean> completionStatus = new ConcurrentHashMap<>();
    
    /**
     * 添加日志条目
     */
    public void addLog(String buildId, String level, String message) {
        if (buildId == null || message == null) return;
        
        LogEntry entry = new LogEntry(System.currentTimeMillis(), level, message);
        
        // 存入缓冲区
        buffers.computeIfAbsent(buildId, k -> new CopyOnWriteArrayList<>()).add(entry);
        
        // 推送给所有订阅者
        CopyOnWriteArrayList<Consumer<LogEntry>> subs = subscribers.get(buildId);
        if (subs != null) {
            for (Consumer<LogEntry> sub : subs) {
                try {
                    sub.accept(entry);
                } catch (Exception e) {
                    log.debug("Failed to push log to subscriber: {}", e.getMessage());
                }
            }
        }
    }
    
    /**
     * 获取所有历史日志
     */
    public List<LogEntry> getHistory(String buildId) {
        CopyOnWriteArrayList<LogEntry> buffer = buffers.get(buildId);
        return buffer != null ? List.copyOf(buffer) : List.of();
    }
    
    /**
     * 获取指定索引之后的日志
     */
    public List<LogEntry> getLogsAfter(String buildId, int afterIndex) {
        CopyOnWriteArrayList<LogEntry> buffer = buffers.get(buildId);
        if (buffer == null || afterIndex >= buffer.size()) {
            return List.of();
        }
        return List.copyOf(buffer.subList(afterIndex, buffer.size()));
    }
    
    /**
     * 订阅日志（SSE 用）
     */
    public void subscribe(String buildId, Consumer<LogEntry> subscriber) {
        subscribers.computeIfAbsent(buildId, k -> new CopyOnWriteArrayList<>()).add(subscriber);
    }
    
    /**
     * 取消订阅
     */
    public void unsubscribe(String buildId, Consumer<LogEntry> subscriber) {
        CopyOnWriteArrayList<Consumer<LogEntry>> subs = subscribers.get(buildId);
        if (subs != null) {
            subs.remove(subscriber);
        }
    }
    
    /**
     * 标记构建完成（带状态）
     * @param buildId 构建 ID
     * @param success 是否成功
     */
    public void markCompleted(String buildId, boolean success) {
        completed.put(buildId, true);
        completionStatus.put(buildId, success);
    }
    
    /**
     * 检查构建是否完成
     */
    public boolean isCompleted(String buildId) {
        return Boolean.TRUE.equals(completed.get(buildId));
    }
    
    /**
     * 获取构建完成状态
     * @return true=成功, false=失败, null=未完成
     */
    public Boolean getCompletionStatus(String buildId) {
        if (!isCompleted(buildId)) {
            return null;
        }
        return completionStatus.get(buildId);
    }
    
    /**
     * 导出日志为纯文本格式
     * @param buildId 构建 ID
     * @return 日志文本内容
     */
    public String exportAsText(String buildId) {
        List<LogEntry> entries = getHistory(buildId);
        if (entries.isEmpty()) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        java.time.format.DateTimeFormatter formatter = 
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
        
        for (LogEntry entry : entries) {
            java.time.LocalDateTime time = java.time.Instant.ofEpochMilli(entry.timestamp())
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDateTime();
            sb.append(String.format("[%s] [%s] %s%n", 
                formatter.format(time), 
                entry.level().toUpperCase(), 
                entry.message()));
        }
        
        return sb.toString();
    }
    
    /**
     * 清理缓冲区（构建完成后延迟清理）
     */
    public void scheduleCleanup(String buildId, long delayMinutes) {
        // 简单实现：使用虚拟线程延迟清理
        Thread.startVirtualThread(() -> {
            try {
                TimeUnit.MINUTES.sleep(delayMinutes);
                buffers.remove(buildId);
                subscribers.remove(buildId);
                completed.remove(buildId);
                completionStatus.remove(buildId);
                log.debug("Cleaned up build log buffer for: {}", buildId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }
}
