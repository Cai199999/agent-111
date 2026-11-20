package com.wuye.agent.metrics;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!mysql")
public class InMemoryLlmUsageStore implements LlmUsageStore {

    private final List<LlmUsageEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void record(LlmUsageEvent event) {
        events.add(event);
    }

    @Override
    public LlmUsageSummary summary(Instant from, Instant to) {
        List<LlmUsageEvent> filtered = events.stream()
                .filter(event -> from == null || !event.createdAt().isBefore(from))
                .filter(event -> to == null || !event.createdAt().isAfter(to))
                .toList();
        return new LlmUsageSummary(
                filtered.size(),
                filtered.stream().mapToLong(LlmUsageEvent::promptTokens).sum(),
                filtered.stream().mapToLong(LlmUsageEvent::completionTokens).sum(),
                filtered.stream().mapToLong(LlmUsageEvent::totalTokens).sum(),
                filtered.stream().mapToDouble(LlmUsageEvent::costCny).sum());
    }
}
