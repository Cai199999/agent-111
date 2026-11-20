package com.wuye.agent.api;

import com.wuye.agent.common.ApiResponse;
import com.wuye.agent.common.Ids;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 原型身份接口。不实现真实密码校验；生产化替换为 JWT + 用户表。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final Ids ids;

    public AuthController(Ids ids) {
        this.ids = ids;
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody(required = false) Map<String, String> request,
                                                  @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        String username = request == null ? "admin" : request.getOrDefault("username", "admin");
        return ApiResponse.ok(Map.of(
                "accessToken", "prototype-token",
                "expiresIn", 7200,
                "user", Map.of(
                        "userNo", "U0001",
                        "username", username,
                        "displayName", "原型管理员",
                        "roles", List.of("ADMIN")
                )), trace(traceId));
    }

    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me(@RequestHeader(value = "X-Actor-Id", defaultValue = "10001") String actorId,
                                               @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        return ApiResponse.ok(Map.of(
                "userNo", "U0001",
                "actorId", actorId,
                "username", "admin",
                "displayName", "原型管理员",
                "roles", List.of("ADMIN")
        ), trace(traceId));
    }

    private String trace(String traceId) {
        return traceId == null || traceId.isBlank() ? ids.traceId() : traceId;
    }
}
