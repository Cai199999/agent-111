package com.wuye.agent.routing;

import java.util.Map;
import java.util.Optional;

/**
 * LLM 路由/规划/修正客户端，便于测试替换。
 */
public interface LlmRouterClient {

    Optional<RouteDecision> route(String message);

    default Optional<RouteDecision> route(String message, PendingIntent pending) {
        return route(message);
    }

    default Optional<RouteDecision> route(String message, PendingIntent pending, String sessionSummary) {
        return route(message, pending);
    }

    default Optional<RouteDecision> route(String message, PendingIntent pending, String sessionSummary,
                                          String userProfile) {
        return route(message, pending, sessionSummary);
    }

    default Optional<String> summarize(String sessionNo, String previousSummary,
                                       java.util.List<java.util.Map<String, String>> messages) {
        return Optional.empty();
    }

    default Optional<com.wuye.agent.memory.UserProfileOutcome> extractProfile(
            String userId, Map<String, Object> currentProfile, String sessionSummary,
            java.util.List<java.util.Map<String, String>> messages) {
        return Optional.empty();
    }

    default Optional<RepairOutcome> repair(String userMessage, String capabilityId,
                                           Map<String, Object> args,
                                           String errorCode, String errorMessage) {
        return Optional.empty();
    }

    default Optional<String> explain(String userMessage, String capabilityId, Object output) {
        return Optional.empty();
    }
}
