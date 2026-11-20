package com.wuye.agent.agent.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import java.util.List;

/**
 * 人工确认。token 只存服务端，永不进入 API/SSE 序列化。
 */
public record Confirmation(
        @JsonIgnore String token,
        String title,
        String riskLevel,
        List<Field> fields,
        Instant expiresAt,
        String status
) {
    public record Field(String label, String value) {
    }

    public Confirmation withStatus(String newStatus) {
        return new Confirmation(token, title, riskLevel, fields, expiresAt, newStatus);
    }

    public boolean expired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }
}
