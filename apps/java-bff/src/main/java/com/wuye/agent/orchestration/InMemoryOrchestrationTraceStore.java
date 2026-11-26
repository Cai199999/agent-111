package com.wuye.agent.orchestration;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 进程内编排审计（默认/测试 profile），有界 200 条。
 */
@Component
@Profile("!mysql")
public class InMemoryOrchestrationTraceStore implements OrchestrationTraceStore {

    private static final int CAPACITY = 200;

    private final Deque<OrchestrationTrace> traces = new ArrayDeque<>();

    @Override
    public synchronized void append(OrchestrationTrace trace) {
        if (trace == null) {
            return;
        }
        traces.addLast(trace);
        while (traces.size() > CAPACITY) {
            traces.removeFirst();
        }
    }

    @Override
    public synchronized List<OrchestrationTrace> recent(int limit) {
        int size = Math.max(0, Math.min(limit, traces.size()));
        int skip = Math.max(0, traces.size() - size);
        List<OrchestrationTrace> list = new ArrayList<>(size);
        int index = 0;
        for (OrchestrationTrace trace : traces) {
            if (index++ >= skip) {
                list.add(trace);
            }
        }
        return list;
    }

    @Override
    public synchronized Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("backend", "MEMORY");
        stats.put("capacity", CAPACITY);
        stats.put("size", traces.size());
        Map<String, Long> verdicts = new LinkedHashMap<>();
        for (OrchestrationTrace trace : traces) {
            verdicts.merge(trace.verdict() == null ? "UNKNOWN" : trace.verdict(),
                    1L, Long::sum);
        }
        stats.put("verdicts", verdicts);
        return stats;
    }
}
