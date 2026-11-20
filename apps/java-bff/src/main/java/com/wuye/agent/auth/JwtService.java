package com.wuye.agent.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import javax.crypto.SecretKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * HMAC-SHA256 JWT 签发/校验。密钥只从环境变量读取。
 */
@Component
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private final SecretKey key;
    private final long ttlSeconds;

    public JwtService(@Value("${wuye.auth.jwt-secret:}") String configuredSecret,
                      @Value("${wuye.auth.token-ttl-seconds:28800}") long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
        byte[] raw;
        if (configuredSecret == null || configuredSecret.isBlank()) {
            raw = new byte[32];
            new SecureRandom().nextBytes(raw);
            log.warn("未配置 JWT_SECRET，使用进程内随机密钥；重启后旧 token 全部失效");
        } else {
            raw = sha256(configuredSecret);
        }
        this.key = Keys.hmacShaKeyFor(raw);
    }

    public String issue(AuthUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(user.id()))
                .claim("username", user.username())
                .claim("roles", user.roles())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(key)
                .compact();
    }

    public Optional<Long> parseUserId(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
            return Optional.of(Long.parseLong(claims.getSubject()));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("无法初始化 JWT 密钥", ex);
        }
    }
}
