package com.wuye.agent.audit;

import java.util.List;

public interface AuditStore {

    void record(AuditEvent event);

    List<AuditEvent> recent(int limit, String action);
}
