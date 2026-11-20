package com.wuye.agent.metrics;

public record LlmUsageSummary(
        long calls,
        long promptTokens,
        long completionTokens,
        long totalTokens,
        double costCny
) {
    public static LlmUsageSummary empty() {
        return new LlmUsageSummary(0, 0, 0, 0, 0);
    }
}
