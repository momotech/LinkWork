package com.linkwork.model.dto.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * IMAGE_PULLING / IMAGE_PULLED 事件数据
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImageEventData {
    
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
     * 镜像地址
     */
    private String image;
}
