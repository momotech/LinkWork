package com.linkwork.model.dto;

import lombok.Builder;
import lombok.Data;

/**
 * PodGroup 状态信息
 */
@Data
@Builder
public class PodGroupStatusInfo {
    private String name;            // PodGroup 名称
    private String phase;           // Pending/Running/Succeeded/Failed/Unknown
    private Integer minMember;      // 最小成员数
    private Integer running;        // 运行中 Pod 数
    private Integer succeeded;      // 成功 Pod 数
    private Integer failed;         // 失败 Pod 数
    private Integer pending;        // 等待中 Pod 数
}
