package com.linkwork.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Slf4j
@Component
public class BuildLogBuffer {

    public record LogEntry(long timestamp, String level, String message) {}

    private final Map<String, CopyOnWriteArrayList<LogEntry>> buffers = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<Consumer<LogEntry>>> subscribers = new ConcurrentHashMap<>();
    private final Map<String, Boolean> completed = new ConcurrentHashMap<>();
    private final Map<String, Boolean> completionStatus = new ConcurrentHashMap<>();

    public void addLog(String buildId, String level, String message) {
        if (buildId == null || message == null) return;
        LogEntry entry = new LogEntry(System.currentTimeMillis(), level, message);
        buffers.computeIfAbsent(buildId, k -> new CopyOnWriteArrayList<>()).add(entry);
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

    public List<LogEntry> getHistory(String buildId) {
        CopyOnWriteArrayList<LogEntry> buffer = buffers.get(buildId);
        return buffer != null ? List.copyOf(buffer) : List.of();
    }

    public List<LogEntry> getLogsAfter(String buildId, int afterIndex) {
        CopyOnWriteArrayList<LogEntry> buffer = buffers.get(buildId);
        if (buffer == null || afterIndex >= buffer.size()) return List.of();
        return List.copyOf(buffer.subList(afterIndex, buffer.size()));
    }

    public void subscribe(String buildId, Consumer<LogEntry> subscriber) {
        subscribers.computeIfAbsent(buildId, k -> new CopyOnWriteArrayList<>()).add(subscriber);
    }

    public void unsubscribe(String buildId, Consumer<LogEntry> subscriber) {
        CopyOnWriteArrayList<Consumer<LogEntry>> subs = subscribers.get(buildId);
        if (subs != null) subs.remove(subscriber);
    }

    public void markCompleted(String buildId, boolean success) {
        completed.put(buildId, true);
        completionStatus.put(buildId, success);
    }

    public boolean isCompleted(String buildId) {
        return Boolean.TRUE.equals(completed.get(buildId));
    }

    public Boolean getCompletionStatus(String buildId) {
        if (!isCompleted(buildId)) return null;
        return completionStatus.get(buildId);
    }

    public String exportAsText(String buildId) {
        List<LogEntry> entries = getHistory(buildId);
        if (entries.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
        for (LogEntry entry : entries) {
            LocalDateTime time = Instant.ofEpochMilli(entry.timestamp())
                    .atZone(ZoneId.systemDefault()).toLocalDateTime();
            sb.append(String.format("[%s] [%s] %s%n", formatter.format(time),
                    entry.level().toUpperCase(), entry.message()));
        }
        return sb.toString();
    }

    public void scheduleCleanup(String buildId, long delayMinutes) {
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
