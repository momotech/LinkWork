package com.linkwork.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 构建队列状态 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BuildQueueStatus {
    
    /**
     * 等待中的任务数量
     */
    private int waitingCount;
    
    /**
     * 正在执行的任务数量
     */
    private int runningCount;
    
    /**
     * 硬性并发上限
     */
    private int maxConcurrent;
    
    /**
     * 队列最大容量
     */
    private int maxQueueSize;
    
    /**
     * 系统资源状态
     */
    private ResourceStatus resourceStatus;
    
    /**
     * CPU 阈值配置
     */
    private double cpuThreshold;
    
    /**
     * 内存阈值配置
     */
    private double memoryThreshold;
    
    /**
     * 是否可以接受新任务
     */
    private boolean canAcceptNewTask;
    
    /**
     * 等待中的任务列表（简要信息）
     */
    private List<TaskInfo> waitingTasks;
    
    /**
     * 正在执行的任务列表（简要信息）
     */
    private List<TaskInfo> runningTasks;
    
    /**
     * 任务简要信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaskInfo {
        /**
         * 构建 ID
         */
        private String buildId;
        
        /**
         * 服务 ID
         */
        private String serviceId;
        
        /**
         * 等待时间（毫秒）
         */
        private long waitTimeMs;
        
        /**
         * 执行时间（毫秒），仅对正在执行的任务有效
         */
        private long executionTimeMs;
        
        /**
         * 在队列中的位置（从 1 开始）
         */
        private int position;
    }
}
