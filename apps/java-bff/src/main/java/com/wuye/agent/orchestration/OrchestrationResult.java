package com.wuye.agent.orchestration;

import com.wuye.agent.routing.RouteDecision;
import java.util.List;

/**
 * Python 编排图的返回结果（计划或验收）。
 *
 * <p>只包含结构化状态与计划，不包含子 Agent 的令牌或内部凭据。</p>
 */
public record OrchestrationResult(
        String verdict,
        String clarification,
        List<RouteDecision.PlanStep> plan,
        List<StepResult> steps,
        List<String> notes
) {

    public record StepResult(
            String capabilityId,
            String agentKey,
            String status,
            Object output,
            String error,
            List<String> missingFields
    ) {
        public StepResult {
            missingFields = missingFields == null ? List.of() : List.copyOf(missingFields);
        }
    }

    public OrchestrationResult {
        plan = plan == null ? List.of() : List.copyOf(plan);
        steps = steps == null ? List.of() : List.copyOf(steps);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public boolean blocking() {
        return "NEEDS_INPUT".equals(verdict) || "FAILED".equals(verdict);
    }

    public boolean dispatchRequired() {
        return "DISPATCH_REQUIRED".equals(verdict);
    }

    public boolean complete() {
        return "COMPLETE".equals(verdict);
    }
}
