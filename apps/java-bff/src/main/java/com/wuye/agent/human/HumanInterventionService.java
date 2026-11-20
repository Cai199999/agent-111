package com.wuye.agent.human;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

@Service
public class HumanInterventionService {

    private final HumanInterventionStore store;

    public HumanInterventionService(HumanInterventionStore store) {
        this.store = store;
    }

    public void upsertPending(String taskNo) {
        store.upsertPending(taskNo);
    }

    public void autoResolve(String taskNo, String note) {
        store.autoResolve(taskNo, note);
    }

    public Optional<HumanTaskRecord> get(String taskNo) {
        return store.get(taskNo);
    }

    public List<HumanTaskRecord> list(String status, int limit) {
        return store.list(status, limit);
    }

    public void claim(String taskNo, long userId, String username) {
        store.claim(taskNo, userId, username);
    }

    public void resolve(String taskNo, String note) {
        store.resolve(taskNo, note);
    }
}
