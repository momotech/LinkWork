package com.linkwork.model.dto.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 构建事件数据
 * 用于 BUILD_STARTED / BUILD_PROGRESS / BUILD_COMPLETED / BUILD_FAILED / BUILD_PUSHING / BUILD_PUSHED 事件
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BuildEventData {
    
    /**
     * 构建编号
     */
    @JsonProperty("build_no")
    private String buildNo;
    
    /**
     * 岗位 ID
     */
    @JsonProperty("role_id")
    private Long roleId;
    
    /**
     * 岗位名称
     */
    @JsonProperty("role_name")
    private String roleName;
    
    /**
     * 基础镜像
     */
    @JsonProperty("base_image")
    private String baseImage;
    
    /**
     * 构建产物镜像标签
     */
    @JsonProperty("image_tag")
    private String imageTag;
    
    /**
     * 进度消息（用于 BUILD_PROGRESS）
     */
    private String message;
    
    /**
     * 进度步骤（用于 BUILD_PROGRESS）
     */
    private String step;
    
    /**
     * 错误码（用于 BUILD_FAILED）
     */
    @JsonProperty("error_code")
    private String errorCode;
    
    /**
     * 错误消息（用于 BUILD_FAILED）
     */
    @JsonProperty("error_message")
    private String errorMessage;
    
    /**
     * 构建耗时（毫秒）
     */
    @JsonProperty("duration_ms")
    private Long durationMs;
}
