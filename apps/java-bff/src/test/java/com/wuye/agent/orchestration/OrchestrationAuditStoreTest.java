package com.wuye.agent.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.wuye.agent.routing.RouteDecision;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * 编排审计：阶段/判定/能力/状态记录，不落 input/output 原文。
 */
class OrchestrationAuditStoreTest {

    @Test
    void recordsPlanAndVerifyWithoutPayloads() {
        OrchestrationAuditStore store = new OrchestrationAuditStore();
        store.record("PLAN", "trc-1", "ws-1", new OrchestrationOutcome(
                "DISPATCH_REQUIRED", null,
                List.of(new RouteDecision.PlanStep("property.profile.get", Map.of())),
                List.of(), List.of()));
        store.record("VERIFY", "trc-1", "ws-1", new OrchestrationOutcome(
                "FAILED", "boom",
                List.of(),
                List.of(new OrchestrationResult.StepResult(
                        "property.ticket.create", "property-maintenance",
                        "FAILED", null, "boom", List.of())),
                List.of()));

        List<OrchestrationAuditStore.Trace> traces = store.recent(10);

        assertEquals(2, traces.size());
        assertEquals("PLAN", traces.get(0).phase());
        assertEquals(List.of("property.profile.get"), traces.get(0).capabilities());
        assertEquals("VERIFY", traces.get(1).phase());
        assertEquals(List.of("property.ticket.create"), traces.get(1).capabilities());
        assertEquals(List.of("FAILED"), traces.get(1).statuses());
        assertEquals("boom", traces.get(1).clarification());
        assertEquals(2, store.stats().get("size"));
    }

    @Test
    void recentKeepsNewestAndHonorsLimit() {
        OrchestrationAuditStore store = new OrchestrationAuditStore();
        for (int i = 0; i < 5; i++) {
            store.record("PLAN", "trc-" + i, "ws", new OrchestrationOutcome(
                    "DISPATCH_REQUIRED", null,
                    List.of(new RouteDecision.PlanStep("cap-" + i, Map.of())),
                    List.of(), List.of()));
        }

        List<OrchestrationAuditStore.Trace> traces = store.recent(2);

        assertEquals(2, traces.size());
        assertEquals("trc-3", traces.get(0).traceId());
        assertEquals("trc-4", traces.get(1).traceId());
    }
}
