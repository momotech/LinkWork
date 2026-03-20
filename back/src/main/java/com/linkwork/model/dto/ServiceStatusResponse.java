package com.linkwork.model.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

/**
 * 服务状态响应（Status 服务输出）
 */
@Data
@Builder
public class ServiceStatusResponse {
    private String serviceId;
    private PodGroupStatusInfo podGroupStatus;  // PodGroup 整体状态
    private List<PodStatusInfo> pods;           // 各 Pod 详细状态
    private Instant createdAt;
    private Instant updatedAt;
}
