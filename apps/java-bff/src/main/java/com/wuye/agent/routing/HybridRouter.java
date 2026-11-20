package com.wuye.agent.routing;

import java.util.Optional;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 规则优先、LLM 兜底：
 * - 规则高置信度：直接使用，省 token/延迟；
 * - 规则歧义/低置信度：调用 Python LLM；
 * - LLM 不可用/超时：回退规则结果，不阻塞对话。
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
        if (!properties.isEnabled()) {
            return rule;
        }
        if (!rule.needClarification() && rule.confidence() >= properties.getRuleConfidenceThreshold()) {
            return rule;
        }
        Optional<RouteDecision> llm = llmRouterClient.route(message);
        return llm.orElse(rule);
    }
}
