package com.linkwork.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 调度模块通用 API 响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduleApiResponse<T> {
    private boolean success;
    private T data;
    private String errorCode;
    private String errorMessage;
    private Instant timestamp;
    
    public static <T> ScheduleApiResponse<T> success(T data) {
        return ScheduleApiResponse.<T>builder()
            .success(true)
            .data(data)
            .timestamp(Instant.now())
            .build();
    }
    
    public static <T> ScheduleApiResponse<T> error(String message) {
        return ScheduleApiResponse.<T>builder()
            .success(false)
            .errorMessage(message)
            .timestamp(Instant.now())
            .build();
    }
    
    public static <T> ScheduleApiResponse<T> error(String code, String message) {
        return ScheduleApiResponse.<T>builder()
            .success(false)
            .errorCode(code)
            .errorMessage(message)
            .timestamp(Instant.now())
            .build();
    }
}
