package com.wuye.agent.orchestration;

import com.wuye.agent.routing.RouteDecision;
import java.util.List;

/**
 * Java 侧消费的编排结果：计划、验收状态与可回问的澄清文本。
 */
public record OrchestrationOutcome(
        String verdict,
        String clarification,
        List<RouteDecision.PlanStep> plan,
        List<OrchestrationResult.StepResult> steps,
        List<String> notes
) {

    public OrchestrationOutcome {
        plan = plan == null ? List.of() : List.copyOf(plan);
        steps = steps == null ? List.of() : List.copyOf(steps);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    /** NEEDS_INPUT / FAILED 时不执行计划，先回问或人工处理。 */
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
