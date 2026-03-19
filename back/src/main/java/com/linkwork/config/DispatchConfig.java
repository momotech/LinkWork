package com.linkwork.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "linkwork.dispatch")
public class DispatchConfig {

    private String defaultWorkstationId = "default";

    private String logStreamPrefix = "logs";

    private String approvalKeyPrefix = "approval";

    public String getTaskQueueKey(Long workstationId) {
        return "workstation:" + resolveWorkstationId(workstationId) + ":tasks";
    }

    public String getLogStreamKey(Long workstationId, String taskNo) {
        return logStreamPrefix + ":" + resolveWorkstationId(workstationId) + ":" + taskNo;
    }

    public String getApprovalRequestKey(Long workstationId) {
        return approvalKeyPrefix + ":" + resolveWorkstationId(workstationId);
    }

    public String getApprovalResponseKey(Long workstationId, String requestId) {
        return approvalKeyPrefix + ":" + resolveWorkstationId(workstationId) + ":response:" + requestId;
    }

    public String getTaskControlQueueKey(Long workstationId) {
        return "workstation:" + resolveWorkstationId(workstationId) + ":control";
    }

    public String getApprovalRequestKeyPattern() {
        return approvalKeyPrefix + ":*";
    }

    public String resolveWorkstationId(Long workstationId) {
        if (workstationId != null && workstationId > 0) {
            return String.valueOf(workstationId);
        }
        return defaultWorkstationId;
    }
}
