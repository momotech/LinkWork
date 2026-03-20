package com.linkwork.model.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 镜像构建结果
 */
@Data
@Builder
public class ImageBuildResult {
    
    /**
     * 构建是否成功
     */
    private boolean success;
    
    /**
     * 构建的 Agent 镜像地址
     * 格式：{registry}/service-{serviceId}-agent:{timestamp}
     */
    private String agentImageTag;
    
    /**
     * 构建的 Runner 镜像地址（Sidecar 模式）
     * 格式：{registry}/service-{serviceId}-runner:{timestamp}
     * Alone 模式时为 null
     */
    private String runnerImageTag;
    
    /**
     * 镜像构建耗时（毫秒）
     */
    private long buildDurationMs;
    
    /**
     * 是否已推送到仓库
     * K8s 模式为 true，Compose 模式为 false
     */
    private boolean pushed;
    
    /**
     * 错误信息（失败时）
     */
    private String errorMessage;
    
    /**
     * 创建成功结果
     */
    public static ImageBuildResult success(String agentImageTag, String runnerImageTag, 
                                           long buildDurationMs, boolean pushed) {
        return ImageBuildResult.builder()
            .success(true)
            .agentImageTag(agentImageTag)
            .runnerImageTag(runnerImageTag)
            .buildDurationMs(buildDurationMs)
            .pushed(pushed)
            .build();
    }
    
    /**
     * 创建失败结果
     */
    public static ImageBuildResult failed(String errorMessage) {
        return ImageBuildResult.builder()
            .success(false)
            .errorMessage(errorMessage)
            .build();
    }
}
