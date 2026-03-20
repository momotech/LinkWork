package com.linkwork.model.dto.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ENV_SETUP / WORKSPACE_INIT 事件数据
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EnvSetupData {
    
    /**
     * Pod 名称
     */
    @JsonProperty("pod_name")
    private String podName;
    
    /**
     * 步骤标识
     * 例如：code_clone, workspace_setup, ssh_config 等
     */
    private String step;
    
    /**
     * 步骤描述消息
     */
    private String message;
}
