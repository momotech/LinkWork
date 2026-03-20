package com.linkwork.model.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 容器状态信息
 */
@Data
@Builder
public class ContainerStatusInfo {
    private String name;            // 容器名称 (agent/runner)
    private boolean ready;          // 是否就绪
    private String state;           // waiting/running/terminated
    private String reason;          // 状态原因
    private Integer exitCode;       // 退出码（terminated 时）
    private Integer restartCount;   // 重启次数
    
    // 资源使用（来自 metrics-server）
    private ResourceUsageInfo resourceUsage;  // 容器级别资源使用
}
