package com.wuye.agent.common;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一异常出口。原型阶段不暴露堆栈，只返回可读错误码。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleApi(ApiException ex, HttpServletRequest request) {
        String traceId = traceId(request);
        return ResponseEntity.status(ex.getHttpStatus())
                .body(ApiResponse.error(ex.getCode(), ex.getMessage(), null, traceId));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex,
                                                              HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(e -> e.getField() + " " + e.getDefaultMessage())
                .orElse("参数校验失败");
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("VALIDATION_ERROR", message, null, traceId(request)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnknown(Exception ex, HttpServletRequest request) {
        String traceId = traceId(request);
        log.error("unexpected error traceId={} path={}", traceId, request.getRequestURI(), ex);
        return ResponseEntity.internalServerError()
                .body(ApiResponse.error("INTERNAL_ERROR", "内部错误", null, traceId));
    }

    private String traceId(HttpServletRequest request) {
        String header = request.getHeader("X-Trace-Id");
        return header == null || header.isBlank() ? "trc_" + UUID.randomUUID().toString().substring(0, 12) : header;
    }
}
