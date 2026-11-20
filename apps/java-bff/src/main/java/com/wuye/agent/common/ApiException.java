package com.wuye.agent.common;

/**
 * 业务异常：携带统一错误码与 HTTP 状态码。
 */
public class ApiException extends RuntimeException {

    private final String code;
    private final int httpStatus;

    public ApiException(String code, int httpStatus, String message) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String getCode() {
        return code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(code, 404, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(code, 409, message);
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(code, 400, message);
    }
}
