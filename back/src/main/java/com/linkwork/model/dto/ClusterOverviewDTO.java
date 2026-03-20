package com.linkwork.model.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ClusterOverviewDTO {
    private String namespace;
    private int totalPods;
    private int runningPods;
    private int pendingPods;
    private int failedPods;
    private int succeededPods;
    private long totalCpuMillicores;
    private long usedCpuMillicores;
    private Double cpuUsagePercent;
    private long totalMemoryBytes;
    private long usedMemoryBytes;
    private Double memoryUsagePercent;
    private int podGroupCount;
    private int nodeCount;
}
