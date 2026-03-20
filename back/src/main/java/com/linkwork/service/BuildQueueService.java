package com.linkwork.service;

import com.linkwork.config.BuildQueueConfig;
import com.linkwork.model.dto.BuildQueueStatus;
import com.linkwork.model.dto.BuildTask;
import com.linkwork.model.dto.MergedConfig;
import com.linkwork.model.dto.ServiceBuildRequest;
import com.linkwork.model.dto.ServiceBuildResult;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 构建队列服务
 * 
 * 基于系统资源（CPU/内存）动态控制并发构建数量
 */
@Service
@Slf4j
public class BuildQueueService {
    
    private final SystemResourceMonitor resourceMonitor;
    private final BuildQueueConfig config;
    private final BuildExecutor buildExecutor;
    
    // 等待队列
    private final LinkedBlockingQueue<BuildTask> waitingQueue;
    
    // 正在执行的任务
    private final ConcurrentHashMap<String, BuildTask> runningTasks = new ConcurrentHashMap<>();
    
    // 调度器
    private ScheduledExecutorService scheduler;
    
    // 任务执行线程池
    private ExecutorService taskExecutor;
    
    // 是否已停止
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    
    public BuildQueueService(SystemResourceMonitor resourceMonitor, 
                            BuildQueueConfig config,
                            BuildExecutor buildExecutor) {
        this.resourceMonitor = resourceMonitor;
        this.config = config;
        this.buildExecutor = buildExecutor;
        this.waitingQueue = new LinkedBlockingQueue<>(config.getMaxQueueSize());
    }
    
    @PostConstruct
    public void start() {
        if (!config.isEnabled()) {
            log.info("BuildQueueService is disabled");
            return;
        }
        
        // 初始化调度器
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "build-queue-scheduler");
            t.setDaemon(true);
            return t;
        });
        
        // 初始化任务执行线程池
        taskExecutor = Executors.newFixedThreadPool(config.getMaxConcurrent(), r -> {
            Thread t = new Thread(r, "build-task-executor");
            t.setDaemon(true);
            return t;
        });
        
        // 启动队列消费线程
        scheduler.scheduleWithFixedDelay(
            this::processQueue, 
            0, 
            config.getCheckInterval(), 
            TimeUnit.MILLISECONDS
        );
        
        log.info("BuildQueueService started: maxConcurrent={}, cpuThreshold={}, memoryThreshold={}, checkInterval={}ms",
            config.getMaxConcurrent(), config.getCpuThreshold(), config.getMemoryThreshold(), config.getCheckInterval());
    }
    
    @PreDestroy
    public void stop() {
        stopped.set(true);
        
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        if (taskExecutor != null) {
            taskExecutor.shutdown();
            try {
                if (!taskExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
                    taskExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                taskExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        log.info("BuildQueueService stopped");
    }
    
    /**
     * 提交构建任务
     * 
     * @param request 构建请求
     * @param mergedConfig 融合后的配置
     * @return 构建任务
     * @throws IllegalStateException 如果队列已满
     */
    public BuildTask submit(ServiceBuildRequest request, MergedConfig mergedConfig) {
        if (!config.isEnabled()) {
            throw new IllegalStateException("Build queue is disabled");
        }
        
        BuildTask task = new BuildTask(request, mergedConfig);
        
        boolean offered = waitingQueue.offer(task);
        if (!offered) {
            throw new IllegalStateException("Build queue is full (max: " + config.getMaxQueueSize() + ")");
        }
        
        log.info("任务入队: buildId={}, serviceId={}, 当前队列长度={}", 
            task.getBuildId(), task.getServiceId(), waitingQueue.size());
        
        return task;
    }
    
    /**
     * 处理队列（定期执行）
     */
    private void processQueue() {
        if (stopped.get()) {
            return;
        }
        
        try {
            // 检查是否达到硬性并发上限
            if (runningTasks.size() >= config.getMaxConcurrent()) {
                log.debug("达到并发上限: {}/{}", runningTasks.size(), config.getMaxConcurrent());
                return;
            }
            
            // 检查系统资源是否充足
            if (!resourceMonitor.hasAvailableResources(
                    config.getCpuThreshold(), 
                    config.getMemoryThreshold())) {
                log.debug("系统资源不足，等待中...");
                return;
            }
            
            // 从队列取出任务执行
            BuildTask task = waitingQueue.poll();
            if (task != null) {
                executeTask(task);
            }
        } catch (Exception e) {
            log.error("处理队列时发生错误", e);
        }
    }
    
    /**
     * 异步执行构建任务
     */
    private void executeTask(BuildTask task) {
        runningTasks.put(task.getBuildId(), task);
        task.markStarted();
        
        long waitTime = task.getWaitTimeMs();
        log.info("开始执行任务: buildId={}, serviceId={}, 等待时间={}ms, 当前并发={}", 
            task.getBuildId(), task.getServiceId(), waitTime, runningTasks.size());
        
        CompletableFuture<ServiceBuildResult> future = CompletableFuture.supplyAsync(() -> {
            try {
                return buildExecutor.execute(task);
            } catch (Throwable t) {
                log.error("构建任务执行失败: buildId={}", task.getBuildId(), t);
                String error = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                task.markFailed(error);
                return ServiceBuildResult.failed(task.getServiceId(), "BUILD_ERROR", error);
            } finally {
                runningTasks.remove(task.getBuildId());
                if (task.getState() == BuildTask.TaskState.RUNNING) {
                    task.markCompleted();
                }
                log.info("任务执行完成: buildId={}, serviceId={}, 执行时间={}ms", 
                    task.getBuildId(), task.getServiceId(), task.getExecutionTimeMs());
            }
        }, taskExecutor);
        
        // 设置超时
        future.orTimeout(config.getTaskTimeout(), TimeUnit.SECONDS)
            .exceptionally(e -> {
                if (e instanceof TimeoutException) {
                    log.error("构建任务超时: buildId={}, timeout={}s", task.getBuildId(), config.getTaskTimeout());
                    task.markFailed("Build timeout after " + config.getTaskTimeout() + " seconds");
                }
                return ServiceBuildResult.failed(task.getServiceId(), "TIMEOUT", e.getMessage());
            });
        
        task.setResultFuture(future);
    }
    
    /**
     * 取消排队中的任务
     * 
     * @param buildId 构建 ID
     * @return true 如果取消成功
     */
    public boolean cancel(String buildId) {
        // 检查是否在运行中
        if (runningTasks.containsKey(buildId)) {
            log.warn("无法取消正在执行的任务: buildId={}", buildId);
            return false;
        }
        
        // 从等待队列中移除
        boolean removed = waitingQueue.removeIf(task -> task.getBuildId().equals(buildId));
        if (removed) {
            log.info("任务已取消: buildId={}", buildId);
        }
        return removed;
    }
    
    /**
     * 获取任务在队列中的位置
     * 
     * @param buildId 构建 ID
     * @return 位置（从 1 开始），-1 表示正在执行，null 表示不存在
     */
    public Integer getPosition(String buildId) {
        // 检查是否正在执行
        if (runningTasks.containsKey(buildId)) {
            return -1;
        }
        
        // 查找在等待队列中的位置
        int position = 1;
        for (BuildTask task : waitingQueue) {
            if (task.getBuildId().equals(buildId)) {
                return position;
            }
            position++;
        }
        
        return null;
    }
    
    /**
     * 获取队列状态
     */
    public BuildQueueStatus getStatus() {
        List<BuildQueueStatus.TaskInfo> waitingTaskInfos = new ArrayList<>();
        int position = 1;
        for (BuildTask task : waitingQueue) {
            waitingTaskInfos.add(BuildQueueStatus.TaskInfo.builder()
                .buildId(task.getBuildId())
                .serviceId(task.getServiceId())
                .waitTimeMs(task.getWaitTimeMs())
                .position(position++)
                .build());
        }
        
        List<BuildQueueStatus.TaskInfo> runningTaskInfos = runningTasks.values().stream()
            .map(task -> BuildQueueStatus.TaskInfo.builder()
                .buildId(task.getBuildId())
                .serviceId(task.getServiceId())
                .waitTimeMs(task.getWaitTimeMs())
                .executionTimeMs(task.getExecutionTimeMs())
                .position(-1)
                .build())
            .collect(Collectors.toList());
        
        boolean canAccept = waitingQueue.remainingCapacity() > 0 
            && resourceMonitor.hasAvailableResources(config.getCpuThreshold(), config.getMemoryThreshold());
        
        return BuildQueueStatus.builder()
            .waitingCount(waitingQueue.size())
            .runningCount(runningTasks.size())
            .maxConcurrent(config.getMaxConcurrent())
            .maxQueueSize(config.getMaxQueueSize())
            .resourceStatus(resourceMonitor.getStatus())
            .cpuThreshold(config.getCpuThreshold())
            .memoryThreshold(config.getMemoryThreshold())
            .canAcceptNewTask(canAccept)
            .waitingTasks(waitingTaskInfos)
            .runningTasks(runningTaskInfos)
            .build();
    }
    
    /**
     * 检查队列是否启用
     */
    public boolean isEnabled() {
        return config.isEnabled();
    }
    
    /**
     * 获取等待中的任务数
     */
    public int getWaitingCount() {
        return waitingQueue.size();
    }
    
    /**
     * 获取正在执行的任务数
     */
    public int getRunningCount() {
        return runningTasks.size();
    }
}
