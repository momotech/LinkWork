package com.linkwork.common;

import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/**
 * 统一 API 响应结构
 */
@Data
public class ApiResponse<T> {

    private Integer code;
    private String msg;
    private T data;
    private String traceId;

    private String timestamp;

    private ApiResponse(Integer code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
        this.traceId = UUID.randomUUID().toString();
        this.timestamp = Instant.now().toString();
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(0, "success", data);
    }

    public static <T> ApiResponse<T> success() {
        return new ApiResponse<>(0, "success", null);
    }

    public static <T> ApiResponse<T> error(Integer code, String msg) {
        return new ApiResponse<>(code, msg, null);
    }

    public static <T> ApiResponse<T> error(String msg) {
        return new ApiResponse<>(50000, msg, null);
    }

    public static <T> ApiResponse<T> error(Integer code, String msg, T data) {
        return new ApiResponse<>(code, msg, data);
    }
}
