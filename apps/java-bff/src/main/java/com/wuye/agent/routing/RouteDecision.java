package com.wuye.agent.routing;

import java.util.Map;

/**
 * 路由结果。LLM 路由接入后沿用同一结构，Java 侧仍负责能力白名单校验。
 */
public record RouteDecision(
        String capabilityId,
        Map<String, Object> args,
        double confidence,
        boolean needClarification,
        String clarification,
        String reason
) {
    public static RouteDecision route(String capabilityId, Map<String, Object> args,
                                      double confidence, String reason) {
        return new RouteDecision(capabilityId, args, confidence, false, null, reason);
    }

    public static RouteDecision clarify(String question, String reason) {
        return new RouteDecision(null, Map.of(), 0, true, question, reason);
    }
}
