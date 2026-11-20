package com.wuye.agent.audit;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!mysql")
public class InMemoryAuditStore implements AuditStore {

    private final List<AuditEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void record(AuditEvent event) {
        events.add(event);
    }

    @Override
    public List<AuditEvent> recent(int limit, String action) {
        return events.stream()
                .filter(event -> action == null || action.isBlank() || action.equals(event.action()))
                .sorted(Comparator.comparing(AuditEvent::createdAt).reversed())
                .limit(Math.max(1, Math.min(limit, 200)))
                .toList();
    }
}
