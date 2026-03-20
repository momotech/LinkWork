package com.linkwork.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 任务调度配置（与 momo-worker 对齐）
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "robot.dispatch")
public class DispatchConfig {

    /** 默认 workstationId（仅 roleId 缺失时兜底） */
    private String workstationId = "test-post-001";

    /** 日志流 key 前缀 */
    private String logStreamPrefix = "logs";

    /** 审批 key 前缀 */
    private String approvalKeyPrefix = "approval";

    // ==================== 派生 Key 方法 ====================

    /** 任务调度队列 key: workstation:{workstationId}:tasks */
    public String getTaskQueueKey(Long roleId) {
        return "workstation:" + resolveWorkstationId(roleId) + ":tasks";
    }

    /** 日志流 key: logs:{workstationId}:{taskId} */
    public String getLogStreamKey(Long roleId, String taskId) {
        return logStreamPrefix + ":" + resolveWorkstationId(roleId) + ":" + taskId;
    }

    /** 审批请求队列 key: approval:{workstationId} */
    public String getApprovalRequestKey(Long roleId) {
        return approvalKeyPrefix + ":" + resolveWorkstationId(roleId);
    }

    /** 审批响应 key: approval:{workstationId}:response:{requestId} */
    public String getApprovalResponseKey(Long roleId, String requestId) {
        return approvalKeyPrefix + ":" + resolveWorkstationId(roleId) + ":response:" + requestId;
    }


    /** 任务终止控制队列 key: workstation:{workstationId}:control */
    public String getTaskControlQueueKey(Long roleId) {
        return "workstation:" + resolveWorkstationId(roleId) + ":control";
    }

    /** approval 请求队列匹配模式（含 response key，调用方需过滤） */
    public String getApprovalRequestKeyPattern() {
        return approvalKeyPrefix + ":*";
    }

    /**
     * roleId -> workstationId 解析规则：
     * 1) roleId 存在时直接使用 roleId（真实岗位链路）
     * 2) 否则回退默认配置
     */
    public String resolveWorkstationId(Long roleId) {
        if (roleId != null && roleId > 0) {
            return String.valueOf(roleId);
        }
        return workstationId;
    }

    // ==================== 兼容旧调用（默认 workstation） ====================

    /**
     * @deprecated use {@link #getTaskQueueKey(Long)}.
     */
    @Deprecated
    public String getTaskQueueKey() {
        return getTaskQueueKey(null);
    }

    /**
     * @deprecated use {@link #getLogStreamKey(Long, String)}.
     */
    @Deprecated
    public String getLogStreamKey(String taskId) {
        return getLogStreamKey(null, taskId);
    }

    /**
     * @deprecated use {@link #getApprovalRequestKey(Long)}.
     */
    @Deprecated
    public String getApprovalRequestKey() {
        return getApprovalRequestKey(null);
    }

    /**
     * @deprecated use {@link #getApprovalResponseKey(Long, String)}.
     */
    @Deprecated
    public String getApprovalResponseKey(String requestId) {
        return getApprovalResponseKey(null, requestId);
    }

    /**
     * @deprecated use {@link #getTaskControlQueueKey(Long)}.
     */
    @Deprecated
    public String getTaskControlQueueKey() {
        return getTaskControlQueueKey(null);
    }

    /**
     * @deprecated use {@link #getTaskControlQueueKey(Long)}.
     */
    @Deprecated
    public String getTaskTerminateQueueKey() {
        return getTaskControlQueueKey(null);
    }
}
