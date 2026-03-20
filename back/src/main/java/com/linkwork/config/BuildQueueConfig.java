package com.linkwork.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 构建队列配置
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "build-queue")
public class BuildQueueConfig {
    
    /**
     * CPU 使用率阈值 (0.0 ~ 1.0)
     * 当系统 CPU 使用率低于此值时，才允许启动新的构建任务
     * 默认 0.7 (70%)
     */
    private double cpuThreshold = 0.7;
    
    /**
     * 内存使用率阈值 (0.0 ~ 1.0)
     * 当系统内存使用率低于此值时，才允许启动新的构建任务
     * 默认 0.7 (70%)
     */
    private double memoryThreshold = 0.7;
    
    /**
     * 硬性并发上限
     * 即使系统资源充足，同时运行的构建任务也不会超过此值
     * 这是一个安全阀，防止资源监控失效时系统过载
     * 默认 3
     */
    private int maxConcurrent = 3;
    
    /**
     * 队列最大容量
     * 超过此容量时，新任务将被拒绝
     * 默认 50
     */
    private int maxQueueSize = 50;
    
    /**
     * 构建超时时间（秒）
     * 单个构建任务的最大执行时间
     * 默认 600 秒 (10 分钟)
     */
    private int taskTimeout = 600;
    
    /**
     * 资源检查间隔（毫秒）
     * 队列消费线程检查资源的间隔
     * 默认 1000 毫秒 (1 秒)
     */
    private long checkInterval = 1000;
    
    /**
     * 残留文件清理阈值（小时）
     * 超过此时间的临时构建目录会被清理
     * 默认 1 小时
     */
    private int staleContextHours = 1;
    
    /**
     * 是否启用队列功能
     * 设为 false 时，构建任务将直接异步执行（兼容旧行为）
     * 默认 true
     */
    private boolean enabled = true;
}
