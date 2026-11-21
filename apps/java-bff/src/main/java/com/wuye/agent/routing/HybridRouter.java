package com.wuye.agent.routing;

import java.util.Optional;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * LLM 主导、规则兜底：
 * - 正常情况优先使用 Python LLM 的路由/规划结果；
 * - LLM 未启用/超时/不可用：回退规则结果，不阻塞对话。
 */
@Component
@Primary
public class HybridRouter implements Router {

    private final RuleRouter ruleRouter;
    private final LlmRouterClient llmRouterClient;
    private final AiProperties properties;

    public HybridRouter(RuleRouter ruleRouter, LlmRouterClient llmRouterClient, AiProperties properties) {
        this.ruleRouter = ruleRouter;
        this.llmRouterClient = llmRouterClient;
        this.properties = properties;
    }

    @Override
    public RouteDecision route(String message) {
        RouteDecision rule = ruleRouter.route(message);
        // 多操作请求（如两个工单分别派两个人）优先走确定性 batch，
        // 避免 LLM 把它压成一个 assign 后反复追问 ticketNo。
        if ("batch.execute".equals(rule.capabilityId())) {
            return rule;
        }
        if (!properties.isEnabled()) {
            return rule;
        }
        Optional<RouteDecision> llm = llmRouterClient.route(message);
        return llm.orElse(rule);
    }

    @Override
    public RouteDecision route(String message, PendingIntent pending) {
        return route(message, pending, null);
    }

    @Override
    public RouteDecision route(String message, PendingIntent pending, String sessionSummary) {
        return route(message, pending, sessionSummary, null);
    }

    @Override
    public RouteDecision route(String message, PendingIntent pending, String sessionSummary,
                               String userProfile) {
        RouteDecision rule = ruleRouter.route(message);
        if ("batch.execute".equals(rule.capabilityId())) {
            return rule;
        }
        if (!properties.isEnabled()) {
            return rule;
        }
        Optional<RouteDecision> llm = llmRouterClient.route(message, pending, sessionSummary, userProfile);
        return llm.orElse(rule);
    }
}
