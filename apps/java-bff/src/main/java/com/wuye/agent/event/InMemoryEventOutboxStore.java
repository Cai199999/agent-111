package com.wuye.agent.event;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 内存 Outbox（默认/测试 profile）。
 *
 * <p>只保证同一进程内的“先记录、再投递”；重启恢复由 mysql profile 的
 * {@link MySqlEventOutboxStore} 提供。</p>
 */
@Component
@Profile("!mysql")
public class InMemoryEventOutboxStore implements EventOutboxStore {

    private static final int HISTORY_SIZE = 500;

    private final int maxAttempts;
    private final Map<String, EventEnvelope> pending = new LinkedHashMap<>();
    private final Map<String, Integer> attempts = new LinkedHashMap<>();
    private final Deque<EventEnvelope> history = new ArrayDeque<>();
    private long appended;
    private long dispatched;
    private long failed;

    public InMemoryEventOutboxStore(
            @Value("${wuye.event.outbox.max-attempts:5}") int maxAttempts) {
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    @Override
    public synchronized void append(EventEnvelope event) {
        if (event == null) {
            return;
        }
        pending.putIfAbsent(event.eventId(), event);
        history.addLast(event);
        while (history.size() > HISTORY_SIZE) {
            history.removeFirst();
        }
        appended++;
    }

    @Override
    public synchronized List<EventEnvelope> pending(int limit) {
        int size = Math.max(0, Math.min(limit, pending.size()));
        return new ArrayList<>(pending.values()).subList(0, size);
    }

    @Override
    public synchronized List<EventEnvelope> recent(int limit) {
        int size = Math.max(0, Math.min(limit, history.size()));
        int skip = Math.max(0, history.size() - size);
        List<EventEnvelope> list = new ArrayList<>(size);
        int index = 0;
        for (EventEnvelope event : history) {
            if (index++ >= skip) {
                list.add(event);
            }
        }
        return list;
    }

    @Override
    public synchronized void markDispatched(String eventId) {
        if (eventId != null && pending.remove(eventId) != null) {
            attempts.remove(eventId);
            dispatched++;
        }
    }

    @Override
    public synchronized void markFailed(String eventId, String error) {
        if (eventId == null) {
            return;
        }
        int count = attempts.merge(eventId, 1, Integer::sum);
        if (count >= maxAttempts) {
            pending.remove(eventId);
            attempts.remove(eventId);
            failed++;
        }
    }

    @Override
    public synchronized Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("backend", "MEMORY");
        stats.put("pending", pending.size());
        stats.put("appended", appended);
        stats.put("dispatched", dispatched);
        stats.put("failed", failed);
        stats.put("maxAttempts", maxAttempts);
        return stats;
    }
}
