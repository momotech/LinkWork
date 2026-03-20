package com.linkwork.model.dto.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SESSION_START / SESSION_END 事件数据
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionEventData {
    
    /**
     * PodGroup 名称
     */
    @JsonProperty("pod_group_name")
    private String podGroupName;
    
    /**
     * Pod 数量
     */
    @JsonProperty("pod_count")
    private Integer podCount;
    
    /**
     * 是否优雅停止（SESSION_END 时填充）
     */
    private Boolean graceful;
    
    /**
     * 队列名称
     */
    @JsonProperty("queue_name")
    private String queueName;
    
    /**
     * 节点名称
     */
    @JsonProperty("node_name")
    private String nodeName;
}
