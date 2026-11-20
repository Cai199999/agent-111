package com.wuye.agent.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class HybridRouterTest {

    @Test
    void highConfidenceRuleSkipsLlm() {
        AtomicInteger calls = new AtomicInteger();
        LlmRouterClient llm = message -> {
            calls.incrementAndGet();
            return Optional.of(RouteDecision.route("contract.rule.list", Map.of(), 0.95, "llm")
                    .withRouter("LLM"));
        };
        HybridRouter router = new HybridRouter(new RuleRouter(), llm, enabled());

        RouteDecision decision = router.route("我有哪些待处理工单？");

        assertEquals("property.ticket.query", decision.capabilityId());
        assertEquals("RULE", decision.router());
        assertEquals(0, calls.get());
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
    void genericCreateAsksForDetailsWithoutCallingLlm() {
        AtomicInteger calls = new AtomicInteger();
        LlmRouterClient llm = message -> {
            calls.incrementAndGet();
            return Optional.of(RouteDecision.route("property.ticket.create", Map.of(), 0.95, "llm")
                    .withRouter("LLM"));
        };
        HybridRouter router = new HybridRouter(new RuleRouter(), llm, enabled());

        RouteDecision decision = router.route("创建工单");

        assertTrue(decision.needClarification());
        assertEquals("RULE", decision.router());
        assertEquals(0, calls.get());
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
