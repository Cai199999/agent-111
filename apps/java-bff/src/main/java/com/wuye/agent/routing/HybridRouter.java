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
        return choose(rule, llm);
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
        return choose(rule, llm);
    }

    /**
     * 平台目录追问/批量操作：规则已经给出确定性能力时，不因 LLM 的“泛化澄清”而丢掉上下文。
     * 其他场景仍保持 LLM 主导。
     */
    private RouteDecision choose(RouteDecision rule, Optional<RouteDecision> llm) {
        if (llm.isEmpty()) {
            return rule;
        }
        RouteDecision decision = llm.get();
        boolean deterministic = "platform.catalog".equals(rule.capabilityId())
                || "batch.execute".equals(rule.capabilityId());
        if (deterministic && !rule.needClarification() && decision.needClarification()) {
            return rule;
        }
        return decision;
    }
}
