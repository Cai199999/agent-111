package com.wuye.agent.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bearer JWT 鉴权过滤器。静态资源、登录、健康检查放行；其余 /api/v1/** 必须登录。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final AuthService authService;
    private final ObjectMapper objectMapper;

    public AuthFilter(JwtService jwtService, AuthService authService, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.authService = authService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/")
                || path.equals("/index.html")
                || path.equals("/platform-overview.html")
                || path.equals("/favicon.ico")
                || path.equals("/error")
                || path.equals("/health")
                || path.equals("/ready")
                || path.equals("/.well-known/agent-card.json")
                || path.startsWith("/demo-agents/")
                || path.startsWith("/css/")
                || path.startsWith("/js/")
                || path.startsWith("/vendor/")
                || path.equals("/api/v1/internal/notifications")
                || path.equals("/api/v1/auth/login");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        String token = header != null && header.startsWith("Bearer ")
                ? header.substring(7).trim() : null;
        if (token == null || token.isBlank()) {
            unauthorized(response);
            return;
        }
        Optional<Long> userId = jwtService.parseUserId(token);
        if (userId.isEmpty()) {
            unauthorized(response);
            return;
        }
        AuthUser user;
        try {
            user = authService.loadUser(userId.get());
        } catch (Exception ex) {
            unauthorized(response);
            return;
        }
        AuthContext.set(user);
        try {
            filterChain.doFilter(request, response);
        } finally {
            AuthContext.clear();
        }
    }

    private void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(401);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), Map.of(
                "code", "AUTH_UNAUTHORIZED",
                "message", "未登录或令牌无效",
                "data", Map.of(),
                "traceId", "trc_" + UUID.randomUUID().toString().substring(0, 12)));
    }
}
