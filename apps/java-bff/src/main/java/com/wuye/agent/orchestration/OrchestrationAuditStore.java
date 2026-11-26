package com.wuye.agent.orchestration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * 编排审计：把每次 PLAN/VERIFY 规整为可观测记录，再交给存储。
 *
 * <p>只记录阶段/判定/能力/步骤状态与澄清文本，不记录 input/output 原文，
 * 避免把业务数据复制到观测面。memory 有界，MySQL 跨重启保留。</p>
 */
@Component
public class OrchestrationAuditStore {

    private final OrchestrationTraceStore store;

    public OrchestrationAuditStore(OrchestrationTraceStore store) {
        this.store = store;
    }

    public void record(String phase, String traceId, String sessionNo,
                       OrchestrationOutcome outcome) {
        if (outcome == null) {
            return;
        }
        Set<String> capabilities = new LinkedHashSet<>();
        List<String> statuses = new ArrayList<>();
        for (RouteStep step : planAndSteps(outcome)) {
            if (step.capabilityId != null && !step.capabilityId.isBlank()) {
                capabilities.add(step.capabilityId);
            }
            if (step.status != null && !step.status.isBlank()) {
                statuses.add(step.status);
            }
        }
        store.append(new OrchestrationTrace(
                traceId,
                sessionNo,
                phase,
                outcome.verdict(),
                outcome.clarification(),
                List.copyOf(capabilities),
                List.copyOf(statuses),
                Instant.now()));
    }

    public List<OrchestrationTrace> recent(int limit) {
        return store.recent(limit);
    }

    public Map<String, Object> stats() {
        return store.stats();
    }

    private List<RouteStep> planAndSteps(OrchestrationOutcome outcome) {
        List<RouteStep> rows = new ArrayList<>();
        for (var step : outcome.plan()) {
            rows.add(new RouteStep(step.capabilityId(), null));
        }
        for (var step : outcome.steps()) {
            rows.add(new RouteStep(step.capabilityId(), step.status()));
        }
        return rows;
    }

    private record RouteStep(String capabilityId, String status) {
    }
}
