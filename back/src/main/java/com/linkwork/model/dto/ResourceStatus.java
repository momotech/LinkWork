package com.linkwork.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 系统资源状态 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResourceStatus {
    
    /**
     * CPU 使用率 (0.0 ~ 1.0)
     * -1 表示无法获取
     */
    private double cpuUsage;
    
    /**
     * 内存使用率 (0.0 ~ 1.0)
     * -1 表示无法获取
     */
    private double memoryUsage;
    
    /**
     * 总内存（字节）
     */
    private long totalMemory;
    
    /**
     * 可用内存（字节）
     */
    private long freeMemory;
    
    /**
     * 可用处理器数量
     */
    private int availableProcessors;
    
    /**
     * 获取 CPU 使用率百分比字符串
     */
    public String getCpuUsagePercent() {
        if (cpuUsage < 0) return "N/A";
        return String.format("%.1f%%", cpuUsage * 100);
    }
    
    /**
     * 获取内存使用率百分比字符串
     */
    public String getMemoryUsagePercent() {
        if (memoryUsage < 0) return "N/A";
        return String.format("%.1f%%", memoryUsage * 100);
    }
    
    /**
     * 获取可读的内存信息
     */
    public String getMemoryInfo() {
        return String.format("%s / %s", 
            formatBytes(totalMemory - freeMemory), 
            formatBytes(totalMemory));
    }
    
    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
