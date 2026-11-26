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
    void explicitTicketIdRuleBeatsLlmClarification() {
        LlmRouterClient llm = message -> Optional.of(
                RouteDecision.clarify("请补充 ticketId", "missing")
                        .withRouter("LLM"));
        HybridRouter router = new HybridRouter(new RuleRouter(), llm, enabled());

        RouteDecision decision = router.route("把工单 ID 2125796136400281602 派给 10002");

        assertEquals("property.ticket.assign", decision.capabilityId());
        assertEquals(2125796136400281602L, decision.args().get("ticketId"));
        assertEquals("RULE", decision.router());
    }

    @Test
    void explicitParcelNoRuleBeatsLlmClarification() {
        LlmRouterClient llm = message -> Optional.of(
                RouteDecision.clarify("请补充地块编号", "missing")
                        .withRouter("LLM"));
        HybridRouter router = new HybridRouter(new RuleRouter(), llm, enabled());

        RouteDecision decision = router.route("为地块 P-2026-001 创建巡查任务");

        assertEquals("gis.patrol.create", decision.capabilityId());
        assertEquals("P-2026-001", decision.args().get("parcelNo"));
        assertEquals("RULE", decision.router());
    }

    @Test
    void gisPatrolPlanBeatsLlmDecisionWithoutParcelNo() {
        LlmRouterClient llm = message -> Optional.of(
                RouteDecision.route("gis.patrol.create", Map.of(), 0.9, "llm")
                        .withRouter("LLM"));
        HybridRouter router = new HybridRouter(new RuleRouter(), llm, enabled());

        RouteDecision decision = router.route("安排人去江北 A-01 地块做一次变化图斑复核");

        assertEquals("gis.patrol.create", decision.capabilityId());
        assertTrue(decision.hasPlan());
        assertEquals("RULE", decision.router());
    }

    @Test
    void batchExplicitTicketIdsRuleBeatsLlmKeywords() {
        LlmRouterClient llm = message -> Optional.of(
                RouteDecision.route("batch.execute", Map.of("items", java.util.List.of(
                        Map.of("capabilityId", "property.ticket.assign",
                                "args", Map.of("keyword", "工单1")))), 0.9, "llm")
                        .withRouter("LLM"));
        HybridRouter router = new HybridRouter(new RuleRouter(), llm, enabled());

        RouteDecision decision = router.route(
                "把工单 ID 111111 派给 10002，把工单 ID 222222 派给 10003");

        assertEquals("batch.execute", decision.capabilityId());
        assertEquals("RULE", decision.router());
        assertTrue(decision.args().get("items").toString().contains("ticketId"));
    }

    @Test
    void gisPatrolNameParcelFallsBackToRulePlan() {
        LlmRouterClient llm = message -> Optional.of(
                RouteDecision.route("gis.patrol.create",
                        Map.of("parcelNo", "江北 A-01 地块"), 0.9, "llm")
                        .withRouter("LLM"));
        HybridRouter router = new HybridRouter(new RuleRouter(), llm, enabled());

        RouteDecision decision = router.route("安排人去江北 A-01 地块做一次变化图斑复核");

        assertEquals("gis.patrol.create", decision.capabilityId());
        assertTrue(decision.hasPlan());
        assertEquals("RULE", decision.router());
    }

    @Test
    void contractReviewPlanBeatsLlmClarification() {
        LlmRouterClient llm = message -> Optional.of(
                RouteDecision.clarify("请提供 contractId", "missing")
                        .withRouter("LLM"));
        HybridRouter router = new HybridRouter(new RuleRouter(), llm, enabled());

        RouteDecision decision = router.route("帮我审一下物业服务合同");

        assertEquals("contract.review", decision.capabilityId());
        assertTrue(decision.hasPlan());
        assertEquals("RULE", decision.router());
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
