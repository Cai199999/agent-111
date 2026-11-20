package com.wuye.agent.routing;

import java.util.Map;

/**
 * 路由结果。router=RULE/LLM，便于前端和审计区分决策来源。
 */
public record RouteDecision(
        String capabilityId,
        Map<String, Object> args,
        double confidence,
        boolean needClarification,
        String clarification,
        String reason,
        String router
) {
    public static RouteDecision route(String capabilityId, Map<String, Object> args,
                                      double confidence, String reason) {
        return new RouteDecision(capabilityId, args, confidence, false, null, reason, "RULE");
    }

    public static RouteDecision clarify(String question, String reason) {
        return new RouteDecision(null, Map.of(), 0, true, question, reason, "RULE");
    }

    public RouteDecision withRouter(String newRouter) {
        return new RouteDecision(capabilityId, args, confidence, needClarification,
                clarification, reason, newRouter);
    }
}
