package com.linkwork.service;

import com.linkwork.model.dto.ResourceStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;

/**
 * 系统资源监控服务
 * 使用 JDK 内置 OperatingSystemMXBean 获取系统 CPU 和内存使用率
 */
@Service
@Slf4j
public class SystemResourceMonitor {
    
    private final com.sun.management.OperatingSystemMXBean osBean;
    
    public SystemResourceMonitor() {
        this.osBean = (com.sun.management.OperatingSystemMXBean) 
            ManagementFactory.getOperatingSystemMXBean();
        log.info("SystemResourceMonitor initialized");
    }
    
    /**
     * 获取系统 CPU 使用率 (0.0 ~ 1.0)
     * 返回 -1 表示获取失败
     */
    public double getSystemCpuLoad() {
        double cpuLoad = osBean.getCpuLoad();
        // getCpuLoad() 在某些情况下可能返回负值表示不可用
        if (cpuLoad < 0) {
            // 尝试使用 getSystemLoadAverage 作为后备
            double loadAvg = osBean.getSystemLoadAverage();
            if (loadAvg >= 0) {
                // 将 load average 转换为使用率（假设处理器数量）
                int processors = osBean.getAvailableProcessors();
                cpuLoad = Math.min(loadAvg / processors, 1.0);
            } else {
                return -1;
            }
        }
        return cpuLoad;
    }
    
    /**
     * 获取系统内存使用率 (0.0 ~ 1.0)
     * 优先使用 /proc/meminfo 的 MemAvailable（更准确），否则回退到 JDK API
     */
    public double getMemoryUsage() {
        long total = osBean.getTotalMemorySize();
        if (total <= 0) {
            return -1;
        }
        
        // 尝试从 /proc/meminfo 读取 MemAvailable（Linux 专用，更准确）
        try {
            java.nio.file.Path meminfo = java.nio.file.Paths.get("/proc/meminfo");
            if (java.nio.file.Files.exists(meminfo)) {
                for (String line : java.nio.file.Files.readAllLines(meminfo)) {
                    if (line.startsWith("MemAvailable:")) {
                        // 格式: "MemAvailable:   43681980 kB"
                        String[] parts = line.split("\\s+");
                        if (parts.length >= 2) {
                            long availableKb = Long.parseLong(parts[1]);
                            long available = availableKb * 1024;
                            return (double) (total - available) / total;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Failed to read /proc/meminfo: {}", e.getMessage());
        }
        
        // 回退到 JDK API（在非 Linux 或读取失败时）
        long free = osBean.getFreeMemorySize();
        return (double) (total - free) / total;
    }
    
    /**
     * 获取可用内存（字节）
     */
    public long getFreeMemory() {
        return osBean.getFreeMemorySize();
    }
    
    /**
     * 获取总内存（字节）
     */
    public long getTotalMemory() {
        return osBean.getTotalMemorySize();
    }
    
    /**
     * 获取可用处理器数量
     */
    public int getAvailableProcessors() {
        return osBean.getAvailableProcessors();
    }
    
    /**
     * 检查是否有足够资源执行新的构建任务
     * 
     * @param cpuThreshold CPU 使用率阈值 (0.0 ~ 1.0)
     * @param memoryThreshold 内存使用率阈值 (0.0 ~ 1.0)
     * @return true 如果资源充足
     */
    public boolean hasAvailableResources(double cpuThreshold, double memoryThreshold) {
        double cpu = getSystemCpuLoad();
        double memory = getMemoryUsage();
        
        // 如果无法获取资源信息，默认允许执行（容器环境可能获取不准确）
        if (cpu < 0 || memory < 0) {
            log.warn("无法获取系统资源信息: CPU={}, Memory={}，默认允许执行", cpu, memory);
            return true;
        }
        
        boolean available = cpu < cpuThreshold && memory < memoryThreshold;
        
        if (!available) {
            log.debug("资源不足: CPU={}% (阈值{}%), 内存={}% (阈值{}%)", 
                String.format("%.1f", cpu * 100), String.format("%.1f", cpuThreshold * 100), 
                String.format("%.1f", memory * 100), String.format("%.1f", memoryThreshold * 100));
        }
        
        return available;
    }
    
    /**
     * 获取当前资源状态（用于 API 返回）
     */
    public ResourceStatus getStatus() {
        return ResourceStatus.builder()
            .cpuUsage(getSystemCpuLoad())
            .memoryUsage(getMemoryUsage())
            .totalMemory(getTotalMemory())
            .freeMemory(getFreeMemory())
            .availableProcessors(getAvailableProcessors())
            .build();
    }
}
