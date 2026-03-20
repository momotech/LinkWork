package com.linkwork.model.dto.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * POD_SCHEDULING / POD_SCHEDULED 事件数据
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PodSchedulingData {
    
    /**
     * Pod 名称
     */
    @JsonProperty("pod_name")
    private String podName;
    
    /**
     * Pod 索引
     */
    @JsonProperty("pod_index")
    private Integer podIndex;
    
    /**
     * 节点名称（POD_SCHEDULED 时填充）
     */
    @JsonProperty("node_name")
    private String nodeName;
    
    /**
     * 队列名称
     */
    @JsonProperty("queue_name")
    private String queueName;
}
