package com.wuye.agent.orchestration;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * 编排结果近实时审计（进程内有界环形缓冲）。
 *
 * <p>只记录阶段/判定/能力/步骤状态与澄清文本，不记录 input/output 原文，
 * 避免把业务数据复制到观测面。重启后清空；持久化留到后续。</p>
 */
@Component
public class OrchestrationAuditStore {

    private static final int CAPACITY = 200;

    private final Deque<Trace> traces = new ArrayDeque<>();

    public synchronized void record(String phase, String traceId, String sessionNo,
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
        traces.addLast(new Trace(
                traceId,
                sessionNo,
                phase,
                outcome.verdict(),
                outcome.clarification(),
                List.copyOf(capabilities),
                List.copyOf(statuses),
                Instant.now()));
        while (traces.size() > CAPACITY) {
            traces.removeFirst();
        }
    }

    public synchronized List<Trace> recent(int limit) {
        int size = Math.max(0, Math.min(limit, traces.size()));
        int skip = Math.max(0, traces.size() - size);
        List<Trace> list = new ArrayList<>(size);
        int index = 0;
        for (Trace trace : traces) {
            if (index++ >= skip) {
                list.add(trace);
            }
        }
        return list;
    }

    public synchronized Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("capacity", CAPACITY);
        stats.put("size", traces.size());
        Map<String, Long> verdicts = new LinkedHashMap<>();
        for (Trace trace : traces) {
            verdicts.merge(trace.verdict() == null ? "UNKNOWN" : trace.verdict(),
                    1L, Long::sum);
        }
        stats.put("verdicts", verdicts);
        return stats;
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

    public record Trace(
            String traceId,
            String sessionNo,
            String phase,
            String verdict,
            String clarification,
            List<String> capabilities,
            List<String> statuses,
            Instant occurredAt
    ) {
    }
}
