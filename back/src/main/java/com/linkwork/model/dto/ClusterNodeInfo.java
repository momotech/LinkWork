package com.linkwork.model.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ClusterNodeInfo {
    private String name;
    private String status;
    private List<String> roles;
    private String kubeletVersion;
    private long cpuCapacity;
    private long cpuAllocatable;
    private long cpuUsage;
    private Double cpuUsagePercent;
    private long memCapacity;
    private long memAllocatable;
    private long memUsage;
    private Double memUsagePercent;
    private int podCount;
    private int podCapacity;
}
