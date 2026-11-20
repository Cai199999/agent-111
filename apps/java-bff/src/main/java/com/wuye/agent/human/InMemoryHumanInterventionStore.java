package com.wuye.agent.human;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!mysql")
public class InMemoryHumanInterventionStore implements HumanInterventionStore {

    private final Map<String, HumanTaskRecord> records = new ConcurrentHashMap<>();

    @Override
    public void upsertPending(String taskNo) {
        records.computeIfAbsent(taskNo, key -> new HumanTaskRecord(
                key, "PENDING", null, null, null, Instant.now(), Instant.now()));
    }

    @Override
    public Optional<HumanTaskRecord> get(String taskNo) {
        return Optional.ofNullable(records.get(taskNo));
    }

    @Override
    public List<HumanTaskRecord> list(String status, int limit) {
        return records.values().stream()
                .filter(record -> status == null || status.isBlank() || status.equals(record.status()))
                .sorted(Comparator.comparing(HumanTaskRecord::updatedAt).reversed())
                .limit(Math.max(1, Math.min(limit, 200)))
                .toList();
    }

    @Override
    public void claim(String taskNo, long userId, String username) {
        records.computeIfPresent(taskNo, (key, old) -> new HumanTaskRecord(
                key, "CLAIMED", userId, username, old.note(), old.createdAt(), Instant.now()));
    }

    @Override
    public void resolve(String taskNo, String note) {
        records.computeIfPresent(taskNo, (key, old) -> new HumanTaskRecord(
                key, "RESOLVED", old.assigneeId(), old.assigneeName(), note, old.createdAt(), Instant.now()));
    }

    @Override
    public void autoResolve(String taskNo, String note) {
        records.computeIfPresent(taskNo, (key, old) ->
                "RESOLVED".equals(old.status()) ? old : new HumanTaskRecord(
                        key, "RESOLVED", old.assigneeId(), old.assigneeName(),
                        note, old.createdAt(), Instant.now()));
    }
}
