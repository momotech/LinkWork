package com.linkwork.model.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

/**
 * Pod 状态信息
 */
@Data
@Builder
public class PodStatusInfo {
    private String name;            // Pod 名称
    private String phase;           // Pending/Running/Succeeded/Failed
    private String nodeName;        // 所在节点
    private String nodeHostname;    // 节点主机名
    private Instant startTime;      // 启动时间
    private List<ContainerStatusInfo> containers;  // 容器状态列表
    
    // 资源使用（来自 metrics-server）
    private ResourceUsageInfo resourceUsage;  // Pod 级别资源使用汇总
}
