package com.linkwork.model.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 伸缩结果
 */
@Data
@Builder
public class ScaleResult {
    private String serviceId;
    private boolean success;
    
    /**
     * 伸缩类型：SCALE_UP / SCALE_DOWN / NO_CHANGE
     */
    private String scaleType;
    
    /**
     * 伸缩前 Pod 数量
     */
    private int previousPodCount;
    
    /**
     * 伸缩后 Pod 数量
     */
    private int currentPodCount;
    
    /**
     * 最大 Pod 数量（初始配置）
     */
    private int maxPodCount;
    
    /**
     * 当前运行的 Pod 列表
     */
    private List<String> runningPods;
    
    /**
     * 本次新增的 Pod 列表（扩容时）
     */
    private List<String> addedPods;
    
    /**
     * 本次删除的 Pod 列表（缩容时）
     */
    private List<String> removedPods;
    
    /**
     * 错误信息
     */
    private String errorMessage;
    
    public static ScaleResult success(String serviceId, String scaleType, 
                                      int previousCount, int currentCount, int maxCount,
                                      List<String> runningPods, 
                                      List<String> addedPods, 
                                      List<String> removedPods) {
        return ScaleResult.builder()
            .serviceId(serviceId)
            .success(true)
            .scaleType(scaleType)
            .previousPodCount(previousCount)
            .currentPodCount(currentCount)
            .maxPodCount(maxCount)
            .runningPods(runningPods)
            .addedPods(addedPods)
            .removedPods(removedPods)
            .build();
    }
    
    public static ScaleResult noChange(String serviceId, int currentCount, int maxCount, 
                                       List<String> runningPods) {
        return ScaleResult.builder()
            .serviceId(serviceId)
            .success(true)
            .scaleType("NO_CHANGE")
            .previousPodCount(currentCount)
            .currentPodCount(currentCount)
            .maxPodCount(maxCount)
            .runningPods(runningPods)
            .build();
    }
    
    public static ScaleResult failed(String serviceId, String errorMessage) {
        return ScaleResult.builder()
            .serviceId(serviceId)
            .success(false)
            .errorMessage(errorMessage)
            .build();
    }
}
