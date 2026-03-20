package com.linkwork.service.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkwork.config.MemoryConfig;
import com.linkwork.model.dto.MemoryIndexJob;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "memory.enabled", havingValue = "true", matchIfMissing = true)
@Service
@RequiredArgsConstructor
public class MemoryIndexConsumer {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MemoryConfig memoryConfig;
    private final MemoryService memoryService;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private ExecutorService executorService;

    private static final int POLL_INTERVAL_MS = 1000;

    @PostConstruct
    public void start() {
        if (!memoryConfig.isEnabled()) {
            log.info("Memory service disabled, MemoryIndexConsumer not started");
            return;
        }
        executorService = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "memory-index-consumer");
            t.setDaemon(true);
            return t;
        });
        executorService.submit(this::consumeLoop);
        log.info("MemoryIndexConsumer started, queue: {}", memoryConfig.getIndex().getQueueKey());
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (executorService != null) {
            executorService.shutdownNow();
        }
        log.info("MemoryIndexConsumer stopped");
    }

    private void consumeLoop() {
        String queueKey = memoryConfig.getIndex().getQueueKey();
        while (running.get()) {
            try {
                String json = redisTemplate.opsForList().rightPop(queueKey);
                if (json != null) {
                    processJob(json);
                } else {
                    Thread.sleep(POLL_INTERVAL_MS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (running.get()) {
                    log.error("Error consuming memory index job", e);
                    try {
                        Thread.sleep(POLL_INTERVAL_MS * 2L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
    }

    private void processJob(String json) {
        try {
            MemoryIndexJob job = objectMapper.readValue(json, MemoryIndexJob.class);
            log.info("Processing index job: id={}, type={}, source={}",
                    job.getJobId(), job.getJobType(), job.getSource());
            memoryService.processIndexJob(job);
            log.info("Completed index job: id={}", job.getJobId());
        } catch (Exception e) {
            log.error("Failed to process index job: {}", json, e);
        }
    }
}
