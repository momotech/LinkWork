package com.linkwork.model.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 资源使用信息（来自 metrics-server）
 */
@Data
@Builder
public class ResourceUsageInfo {
    private String cpuUsage;         // CPU 使用量，如 "100m" (100 millicores)
    private String memoryUsage;      // 内存使用量，如 "256Mi"
    private Long cpuMillicores;      // CPU 使用量（毫核）
    private Long memoryBytes;        // 内存使用量（字节）
    
    // 资源限制
    private String cpuLimit;         // CPU 限制
    private String memoryLimit;      // 内存限制
    private String cpuRequest;       // CPU 请求
    private String memoryRequest;    // 内存请求
    
    // 使用率（百分比）
    private Double cpuUsagePercent;      // CPU 使用率 (usage/limit)
    private Double memoryUsagePercent;   // 内存使用率 (usage/limit)
}
