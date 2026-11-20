package com.wuye.agent.metrics;

import java.time.Instant;

public record LlmUsageEvent(
        String operation,
        int promptTokens,
        int completionTokens,
        int totalTokens,
        double costCny,
        Instant createdAt
) {
}
