package com.wuye.agent.human;

import java.util.List;
import java.util.Optional;

public interface HumanInterventionStore {

    void upsertPending(String taskNo);

    Optional<HumanTaskRecord> get(String taskNo);

    List<HumanTaskRecord> list(String status, int limit);

    void claim(String taskNo, long userId, String username);

    void resolve(String taskNo, String note);

    void autoResolve(String taskNo, String note);
}
