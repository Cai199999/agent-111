package com.wuye.agent.common;

/**
 * 统一 API 包络。原型阶段只保留最小字段，生产化可追加 details。
 */
public record ApiResponse<T>(String code, String message, T data, String traceId) {

    public static <T> ApiResponse<T> ok(T data, String traceId) {
        return new ApiResponse<>("OK", "ok", data, traceId);
    }

    public static <T> ApiResponse<T> error(String code, String message, T data, String traceId) {
        return new ApiResponse<>(code, message, data, traceId);
    }
}
