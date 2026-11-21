package com.wuye.agent.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class HybridRouterTest {

    @Test
    void llmDecisionIsPreferredOverRule() {
        AtomicInteger calls = new AtomicInteger();
        LlmRouterClient llm = message -> {
            calls.incrementAndGet();
            return Optional.of(RouteDecision.route("contract.rule.list", Map.of(), 0.95, "llm")
                    .withRouter("LLM"));
        };
        HybridRouter router = new HybridRouter(new RuleRouter(), llm, enabled());

        RouteDecision decision = router.route("我有哪些待处理工单？");

        assertEquals("contract.rule.list", decision.capabilityId());
        assertEquals("LLM", decision.router());
        assertEquals(1, calls.get());
    }

    @Test
    void ambiguousRuleUsesLlm() {
        LlmRouterClient llm = message -> Optional.of(
                RouteDecision.route("contract.rule.list", Map.of(), 0.91, "llm")
                        .withRouter("LLM"));
        HybridRouter router = new HybridRouter(new RuleRouter(), llm, enabled());

        RouteDecision decision = router.route("查一下知识库");

        assertEquals("contract.rule.list", decision.capabilityId());
        assertEquals("LLM", decision.router());
    }

    @Test
    void llmCanAskForClarification() {
        AtomicInteger calls = new AtomicInteger();
        LlmRouterClient llm = message -> {
            calls.incrementAndGet();
            return Optional.of(RouteDecision.clarify("请提供工单类型", "缺少必填字段")
                    .withRouter("LLM"));
        };
        HybridRouter router = new HybridRouter(new RuleRouter(), llm, enabled());

        RouteDecision decision = router.route("创建工单");

        assertTrue(decision.needClarification());
        assertEquals("LLM", decision.router());
        assertEquals(1, calls.get());
    }

    @Test
    void catalogFollowUpPrefersDeterministicRuleOverLlmClarification() {
        LlmRouterClient llm = message -> Optional.of(
                RouteDecision.clarify("请说明你想了解哪个功能", "缺少上下文")
                        .withRouter("LLM"));
        HybridRouter router = new HybridRouter(new RuleRouter(), llm, enabled());

        RouteDecision decision = router.route("具体是什么功能");

        assertEquals("platform.catalog", decision.capabilityId());
        assertEquals("RULE", decision.router());
    }

    @Test
    void dynamicCapabilityQueryPrefersRuleOverGenericClarification() {
        LlmRouterClient llm = message -> Optional.of(
                RouteDecision.clarify("请问是物业知识还是合同知识", "缺少域")
                        .withRouter("LLM"));
        HybridRouter router = new HybridRouter(new RuleRouter(), llm, enabled());

        RouteDecision decision = router.route("查询政策：耕地保护");

        assertEquals("policy.qa.ask", decision.capabilityId());
        assertEquals("RULE", decision.router());
    }

    @Test
    void llmFailureFallsBackToRule() {
        LlmRouterClient llm = message -> Optional.empty();
        HybridRouter router = new HybridRouter(new RuleRouter(), llm, enabled());

        RouteDecision decision = router.route("查一下知识库");

        assertTrue(decision.needClarification());
        assertEquals("RULE", decision.router());
    }

    @Test
    void disabledAiUsesRuleOnly() {
        AtomicInteger calls = new AtomicInteger();
        LlmRouterClient llm = message -> {
            calls.incrementAndGet();
            return Optional.empty();
        };
        AiProperties properties = enabled();
        properties.setEnabled(false);
        HybridRouter router = new HybridRouter(new RuleRouter(), llm, properties);

        RouteDecision decision = router.route("查一下知识库");

        assertTrue(decision.needClarification());
        assertEquals(0, calls.get());
    }

    private AiProperties enabled() {
        AiProperties properties = new AiProperties();
        properties.setEnabled(true);
        properties.setConfidenceThreshold(0.55);
        properties.setRuleConfidenceThreshold(0.85);
        return properties;
    }
}
