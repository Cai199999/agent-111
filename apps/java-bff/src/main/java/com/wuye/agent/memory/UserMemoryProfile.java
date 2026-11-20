package com.wuye.agent.memory;

import java.time.Instant;
import java.util.Map;

public record UserMemoryProfile(
        long userId,
        Map<String, Object> profile,
        double confidence,
        String sourceSessionNo,
        Instant updatedAt
) {
}
