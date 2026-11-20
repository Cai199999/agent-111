package com.wuye.agent.metrics;

import java.time.Instant;

public interface LlmUsageStore {

    void record(LlmUsageEvent event);

    LlmUsageSummary summary(Instant from, Instant to);
}
