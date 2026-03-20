package com.linkwork.model.dto;

import com.linkwork.model.enums.SnapshotStatus;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

/**
 * 服务快照（用于快速重启）
 * 
 * 设计说明：
 * - Runner 由运行时 agent 启动，快照不保存 runner 相关信息
 */
@Data
@Builder
public class ServiceSnapshot {
    
    // ========== 服务信息 ==========
    private String serviceId;
    private String userId;
    
    // ========== 原始请求（完整保存，用于恢复）==========
    private String originalRequestJson;    // ServiceBuildRequest JSON
    
    // ========== 镜像信息 ==========
    private String agentImage;             // Agent 镜像地址
    private String podMode;                // SIDECAR / ALONE
    
    // ========== 调度信息（关键：用于节点亲和）==========
    private String lastScheduledNode;      // ★ 上次运行的节点
    
    // ========== 伸缩信息 ==========
    private Integer maxPodCount;           // 最大 Pod 数量（初始配置）
    private Integer currentPodCount;       // 当前 Pod 数量
    private Set<String> runningPodNames;   // 当前运行中的 Pod 名称列表
    private Integer nextPodIndex;          // 下一个 Pod 序号（用于生成唯一 Pod 名称）
    
    // ========== 时间信息 ==========
    private Instant createdAt;             // 首次创建时间
    private Instant lastActiveAt;          // 最后活跃时间
    private Instant shutdownAt;            // 关闭时间
    
    // ========== 快照状态 ==========
    private SnapshotStatus status;         // ACTIVE / EXPIRED
    private Integer resumeCount;           // 恢复次数
    
    /**
     * 快照 24 小时后过期
     */
    public boolean isExpired() {
        return shutdownAt != null && 
               shutdownAt.plus(24, ChronoUnit.HOURS).isBefore(Instant.now());
    }
}
