package com.linkwork.model.dto.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * CONTAINER_STARTING / CONTAINER_READY 事件数据
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContainerEventData {
    
    /**
     * Pod 名称
     */
    @JsonProperty("pod_name")
    private String podName;
    
    /**
     * 容器名称
     */
    @JsonProperty("container_name")
    private String containerName;
    
    /**
     * 是否就绪（CONTAINER_READY 时填充）
     */
    private Boolean ready;
}
