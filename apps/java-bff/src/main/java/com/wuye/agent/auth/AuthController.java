package com.wuye.agent.auth;

import com.wuye.agent.agent.catalog.AgentCatalogService;
import com.wuye.agent.common.ApiResponse;
import com.wuye.agent.common.Ids;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录、当前用户、退出。退出为无状态：前端清除 token；服务端 token 到期失效。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthService authService;
    private final AgentCatalogService catalogService;
    private final Ids ids;

    public AuthController(AuthService authService, AgentCatalogService catalogService, Ids ids) {
        this.authService = authService;
        this.catalogService = catalogService;
        this.ids = ids;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@RequestBody LoginRequest request,
                                            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        if (request == null || request.username() == null || request.password() == null) {
            throw new com.wuye.agent.common.ApiException("VALIDATION_ERROR", 400, "用户名和密码不能为空");
        }
        AuthService.LoginResult result;
        try {
            result = authService.login(request.username().strip(), request.password());
            log.info("login success username={} roles={}", result.user().username(), result.user().roles());
        } catch (RuntimeException ex) {
            log.warn("login failed username={} reason={}", request.username(), ex.getMessage());
            throw ex;
        }
        return ApiResponse.ok(new LoginResponse(
                result.accessToken(),
                "Bearer",
                result.expiresIn(),
                result.user(),
                List.copyOf(result.user().permissions()),
                catalogService.forUser(result.user())), trace(traceId));
    }

    @GetMapping("/me")
    public ApiResponse<MeResponse> me(@RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        AuthUser user = AuthContext.require();
        return ApiResponse.ok(new MeResponse(
                user,
                List.copyOf(user.permissions()),
                catalogService.forUser(user)), trace(traceId));
    }

    @PostMapping("/logout")
    public ApiResponse<MeResponse> logout(@RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        AuthContext.require();
        return ApiResponse.ok(null, trace(traceId));
    }

    private String trace(String traceId) {
        return traceId == null || traceId.isBlank() ? ids.traceId() : traceId;
    }

    public record LoginRequest(String username, String password) {
    }

    public record LoginResponse(
            String accessToken,
            String tokenType,
            long expiresIn,
            AuthUser user,
            List<String> permissions,
            List<AgentCatalogService.AgentView> agents
    ) {
    }

    public record MeResponse(
            AuthUser user,
            List<String> permissions,
            List<AgentCatalogService.AgentView> agents
    ) {
    }
}
