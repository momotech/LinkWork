package com.linkwork.model.dto;

import lombok.Data;

/**
 * 伸缩请求
 */
@Data
public class ScaleRequest {
    /**
     * 目标 Pod 数量（可选）
     * scale-up 时不指定则扩容到 maxPodCount
     * scale 时必填
     */
    private Integer targetPodCount;
    
    /**
     * 要删除的 Pod 名称（scale-down 时必填）
     * 不指定则返回错误，防止误删
     */
    private String podName;
    
    /**
     * 调用来源（用于日志追踪）
     */
    private String source;
}
