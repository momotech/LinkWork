package com.linkwork.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkwork.config.DispatchConfig;
import com.linkwork.model.enums.TaskStatus;

import java.util.Map;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 任务调度队列消费者
 * 从 Redis 队列中消费任务，并分发给 Agent 执行器处理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskDispatchConsumer {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final TaskService taskService;
    private final DispatchConfig dispatchConfig;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private ExecutorService executorService;

    /**
     * 队列阻塞等待超时时间（秒）
     */
    private static final int QUEUE_TIMEOUT_SECONDS = 30;

    /**
     * 消费前延迟时间（毫秒），便于观测队列消息
     * TODO: 生产环境设为 0
     */
    private static final int PRE_CONSUME_DELAY_MS = 5000;

    /**
     * 是否启用后端消费者
     * 设为 false 时，消息留在队列供外部 momo-worker 消费
     */
    private static final boolean CONSUMER_ENABLED = false;

    @PostConstruct
    public void startConsumer() {
        if (!CONSUMER_ENABLED) {
            log.info("后端消费者已禁用，消息将由外部 momo-worker 消费，队列: {}", dispatchConfig.getTaskQueueKey());
            return;
        }

        executorService = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "task-dispatch-consumer");
            thread.setDaemon(true);
            return thread;
        });

        executorService.submit(this::consumeLoop);
        log.info("任务调度消费者已启动，监听队列: {}", dispatchConfig.getTaskQueueKey());
    }

    @PreDestroy
    public void stopConsumer() {
        running.set(false);
        if (executorService != null) {
            executorService.shutdownNow();
        }
        log.info("任务调度消费者已停止");
    }

    /**
     * 消费循环
     */
    private void consumeLoop() {
        while (running.get()) {
            try {
                // 每次循环开始时延迟，便于观测队列消息（生产环境应设为 0）
                if (PRE_CONSUME_DELAY_MS > 0) {
                    Thread.sleep(PRE_CONSUME_DELAY_MS);
                }

                // 非阻塞弹出，便于延迟生效
                String messageJson = redisTemplate.opsForList().rightPop(dispatchConfig.getTaskQueueKey());

                if (messageJson != null) {
                    log.info("从队列取出消息，开始处理...");
                    processTask(messageJson);
                }
            } catch (Exception e) {
                if (running.get()) {
                    log.error("消费任务时发生异常", e);
                    // 避免异常风暴，等待一小段时间后重试
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

    /**
     * 处理单个任务
     * 消息格式: {"task_id": "xxx", "content": "xxx"}
     */
    private void processTask(String messageJson) {
        try {
            // 解析简化的消息格式
            Map<String, String> message = objectMapper.readValue(
                    messageJson, new TypeReference<Map<String, String>>() {});
            
            String taskId = message.get("task_id");
            String content = message.get("content");
            
            log.info("收到任务调度消息: task_id={}, content={}", 
                    taskId, content != null && content.length() > 50 
                            ? content.substring(0, 50) + "..." : content);

            // 1. 更新任务状态为 RUNNING
            updateTaskStatus(taskId, TaskStatus.RUNNING);

            // 2. 发布状态变更事件
            publishEvent(taskId, "TASK_STARTED");

            // 3. TODO: 调用 Agent 执行器 (gRPC 或 HTTP)
            // 这里是占位逻辑，实际应该调用 Agent 执行器
            executeTask(taskId, content);

            log.info("任务调度完成: task_id={}", taskId);
        } catch (Exception e) {
            log.error("处理任务消息失败: {}", messageJson, e);
        }
    }

    /**
     * 更新任务状态
     */
    private void updateTaskStatus(String taskNo, TaskStatus status) {
        try {
            taskService.updateStatus(taskNo, status);
        } catch (Exception e) {
            log.error("更新任务状态失败: taskNo={}", taskNo, e);
        }
    }

    /**
     * 发布任务事件到 Redis Stream
     * 统一使用 stream:task:{taskNo} 格式，与 momo-worker 保持一致
     */
    private void publishEvent(String taskNo, String eventType) {
        Long roleId = null;
        try {
            roleId = taskService.getTaskByNo(taskNo).getRoleId();
        } catch (Exception e) {
            log.debug("发布事件时未找到任务 roleId，回退默认 workstation: taskNo={}", taskNo);
        }
        String streamKey = dispatchConfig.getLogStreamKey(roleId, taskNo);
        redisTemplate.opsForStream().add(streamKey,
                Map.of("event_type", eventType, "task_no", taskNo));
        log.debug("发布事件: streamKey={}, eventType={}", streamKey, eventType);
    }

    /**
     * 执行任务 (占位实现)
     * TODO: 对接真正的 Agent 执行器
     * 
     * @param taskId 任务 ID
     * @param content 任务内容
     */
    private void executeTask(String taskId, String content) {
        log.info("准备执行任务: task_id={}, content={}", taskId, 
                content != null && content.length() > 100 
                        ? content.substring(0, 100) + "..." 
                        : content);

        // TODO: 实际调用 Agent 执行器
        // agentExecutor.execute(taskId, content);
    }

    /**
     * 获取当前队列长度（用于监控）
     */
    public Long getQueueLength() {
        return redisTemplate.opsForList().size(dispatchConfig.getTaskQueueKey());
    }
}
