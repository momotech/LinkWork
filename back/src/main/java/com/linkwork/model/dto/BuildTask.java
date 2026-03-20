package com.linkwork.model.dto;

import lombok.Data;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * 构建任务 DTO
 * 用于队列管理
 */
@Data
public class BuildTask {
    
    /**
     * 构建唯一标识
     */
    private final String buildId;
    
    /**
     * 服务 ID
     */
    private final String serviceId;
    
    /**
     * 原始构建请求
     */
    private final ServiceBuildRequest request;
    
    /**
     * 融合后的配置
     */
    private final MergedConfig config;
    
    /**
     * 任务创建时间（入队时间）
     */
    private final Instant createdAt;
    
    /**
     * 任务开始执行时间
     */
    private Instant startedAt;
    
    /**
     * 任务完成时间
     */
    private Instant completedAt;
    
    /**
     * 任务状态
     */
    private TaskState state;
    
    /**
     * 任务执行结果 Future
     */
    private CompletableFuture<ServiceBuildResult> resultFuture;
    
    /**
     * 错误信息（如果失败）
     */
    private String errorMessage;
    
    public BuildTask(ServiceBuildRequest request, MergedConfig config) {
        this.buildId = request.getBuildId();
        this.serviceId = request.getServiceId();
        this.request = request;
        this.config = config;
        this.createdAt = Instant.now();
        this.state = TaskState.WAITING;
    }
    
    /**
     * 标记任务开始执行
     */
    public void markStarted() {
        this.startedAt = Instant.now();
        this.state = TaskState.RUNNING;
    }
    
    /**
     * 标记任务完成
     */
    public void markCompleted() {
        this.completedAt = Instant.now();
        this.state = TaskState.COMPLETED;
    }
    
    /**
     * 标记任务失败
     */
    public void markFailed(String errorMessage) {
        this.completedAt = Instant.now();
        this.state = TaskState.FAILED;
        this.errorMessage = errorMessage;
    }
    
    /**
     * 标记任务取消
     */
    public void markCancelled() {
        this.completedAt = Instant.now();
        this.state = TaskState.CANCELLED;
    }
    
    /**
     * 获取等待时间（毫秒）
     */
    public long getWaitTimeMs() {
        if (startedAt != null) {
            return startedAt.toEpochMilli() - createdAt.toEpochMilli();
        }
        return Instant.now().toEpochMilli() - createdAt.toEpochMilli();
    }
    
    /**
     * 获取执行时间（毫秒）
     */
    public long getExecutionTimeMs() {
        if (startedAt == null) {
            return 0;
        }
        if (completedAt != null) {
            return completedAt.toEpochMilli() - startedAt.toEpochMilli();
        }
        return Instant.now().toEpochMilli() - startedAt.toEpochMilli();
    }
    
    /**
     * 任务状态枚举
     */
    public enum TaskState {
        /**
         * 等待执行
         */
        WAITING,
        
        /**
         * 正在执行
         */
        RUNNING,
        
        /**
         * 执行完成
         */
        COMPLETED,
        
        /**
         * 执行失败
         */
        FAILED,
        
        /**
         * 已取消
         */
        CANCELLED
    }
}
