package com.wuye.agent.audit;

import java.time.Instant;
import java.util.Map;

public record AuditEvent(
        String traceId,
        Long userId,
        String username,
        String action,
        String resource,
        String resourceId,
        String agentKey,
        String capabilityId,
        String status,
        Map<String, Object> detail,
        Instant createdAt
) {
}
