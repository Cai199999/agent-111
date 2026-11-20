package com.wuye.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuye.agent.auth.AuthContext;
import com.wuye.agent.auth.AuthUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 进程内按用户/IP 的每分钟限流。原型阶段不引入 Redis 计数器；
 * 生产可替换为 Redis + 分布式令牌桶。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    private final ObjectMapper objectMapper;
    private final int perMinute;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimitFilter(ObjectMapper objectMapper,
                           @Value("${wuye.rate-limit.per-minute:300}") int perMinute) {
        this.objectMapper = objectMapper;
        this.perMinute = Math.max(1, perMinute);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        AuthUser user = AuthContext.get();
        String key = user != null ? "u:" + user.id() : "ip:" + request.getRemoteAddr();
        long minute = System.currentTimeMillis() / 60_000;
        Window window = windows.compute(key, (k, old) ->
                old == null || old.minute != minute ? new Window(minute, 1) : new Window(minute, old.count + 1));

        if (window.count > perMinute) {
            response.setStatus(429);
            response.setHeader("Retry-After", "60");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            objectMapper.writeValue(response.getWriter(), Map.of(
                    "code", "RATE_LIMITED",
                    "message", "请求过于频繁，请稍后重试",
                    "data", Map.of(),
                    "traceId", "trc_" + UUID.randomUUID().toString().substring(0, 12)));
            return;
        }
        filterChain.doFilter(request, response);
    }

    private record Window(long minute, int count) {
    }
}
