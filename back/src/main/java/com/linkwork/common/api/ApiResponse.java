package com.linkwork.common.api;

public record ApiResponse<T>(int code, String msg, T data) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "OK", data);
    }

    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(1, message, null);
    }
}
