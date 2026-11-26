package com.wuye.agent.routing;

import java.util.List;
import java.util.Map;

/**
 * LLM 规划/路由结果。plan 最多 3 步，最后一步是最终能力；
 * 前置步骤应为只读，用于补齐最终参数。
 */
public record RouteDecision(
        String capabilityId,
        Map<String, Object> args,
        double confidence,
        boolean needClarification,
        String clarification,
        String reason,
        List<PlanStep> plan,
        List<String> missingFields,
        String router,
        String skillId
) {
    public record PlanStep(String capabilityId, Map<String, Object> args) {
    }

    public static RouteDecision route(String capabilityId, Map<String, Object> args,
                                      double confidence, String reason) {
        return new RouteDecision(capabilityId, args, confidence, false, null, reason,
                List.of(), List.of(), "RULE", null);
    }

    public static RouteDecision clarify(String question, String reason) {
        return new RouteDecision(null, Map.of(), 0, true, question, reason,
                List.of(), List.of(), "RULE", null);
    }

    public RouteDecision withRouter(String newRouter) {
        return new RouteDecision(capabilityId, args, confidence, needClarification,
                clarification, reason, plan, missingFields, newRouter, skillId);
    }

    public boolean hasPlan() {
        return plan != null && !plan.isEmpty();
    }
}
