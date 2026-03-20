package com.linkwork.model.dto.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * INIT_COMPLETE / INIT_FAILED 事件数据
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InitCompleteData {
    
    /**
     * Pod 名称
     */
    @JsonProperty("pod_name")
    private String podName;
    
    /**
     * PodGroup 名称
     */
    @JsonProperty("pod_group_name")
    private String podGroupName;
    
    /**
     * 就绪 Pod 数量
     */
    @JsonProperty("ready_pods")
    private Integer readyPods;
    
    /**
     * 总 Pod 数量
     */
    @JsonProperty("total_pods")
    private Integer totalPods;
    
    /**
     * 错误信息（INIT_FAILED 时填充）
     */
    @JsonProperty("error_message")
    private String errorMessage;
    
    /**
     * 错误码（INIT_FAILED 时填充）
     */
    @JsonProperty("error_code")
    private String errorCode;
}
