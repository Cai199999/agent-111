package com.wuye.agent.memory;

import java.util.Map;

public record UserProfileOutcome(
        Map<String, Object> profile,
        boolean changed,
        double confidence,
        String reason
) {
}
