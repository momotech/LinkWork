package com.linkwork.model.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 停止结果
 */
@Data
@Builder
public class StopResult {
    private String serviceId;
    private boolean success;
    private String errorMessage;
    
    public static StopResult success(String serviceId) {
        return StopResult.builder()
            .serviceId(serviceId)
            .success(true)
            .build();
    }
    
    public static StopResult failed(String serviceId, String errorMessage) {
        return StopResult.builder()
            .serviceId(serviceId)
            .success(false)
            .errorMessage(errorMessage)
            .build();
    }
}
