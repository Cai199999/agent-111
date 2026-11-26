package com.wuye.agent.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class RuleRouterTest {

    private final RuleRouter router = new RuleRouter();

    @Test
    void createTicketPhraseRoutesToCreate() {
        RouteDecision decision = router.route("创建工单：3栋2单元水管漏水");
        assertEquals("property.ticket.create", decision.capabilityId());
        assertEquals("3栋2单元水管漏水", decision.args().get("locationText"));
    }

    @Test
    void bareCreateTicketAsksForDetailsInsteadOfQuerying() {
        RouteDecision decision = router.route("创建工单");
        assertTrue(decision.needClarification());
        assertEquals(null, decision.capabilityId());
        assertTrue(decision.clarification().contains("报修内容"));
    }

    @Test
    void explicitTicketIdAssignUsesNumericId() {
        RouteDecision decision = router.route("把工单 ID 2125796136400281602 派给 10002");

        assertEquals("property.ticket.assign", decision.capabilityId());
        assertEquals(2125796136400281602L, decision.args().get("ticketId"));
        assertEquals("10002", decision.args().get("assigneeId"));
        assertEquals(false, decision.needClarification());
    }

    @Test
    void explicitTicketIdCloseUsesNumericId() {
        RouteDecision decision = router.route("关闭工单 ID 2125796136400281602");

        assertEquals("property.ticket.close", decision.capabilityId());
        assertEquals(2125796136400281602L, decision.args().get("ticketId"));
        assertEquals(false, decision.needClarification());
    }

    @Test
    void batchAssignWithExplicitTicketIds() {
        RouteDecision decision = router.route(
                "把工单 ID 111111 派给 10002，把工单 ID 222222 派给 10003");

        assertEquals("batch.execute", decision.capabilityId());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) decision.args().get("items");
        assertEquals(2, items.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) items.get(0).get("args");
        @SuppressWarnings("unchecked")
        Map<String, Object> second = (Map<String, Object>) items.get(1).get("args");
        assertEquals(111111L, first.get("ticketId"));
        assertEquals("10002", first.get("assigneeId"));
        assertEquals(222222L, second.get("ticketId"));
        assertEquals("10003", second.get("assigneeId"));
    }

    @Test
    void contractReviewByNameUsesCleanResolveQuery() {
        RouteDecision decision = router.route("帮我审一下物业服务合同");

        assertEquals("contract.review", decision.capabilityId());
        assertTrue(decision.hasPlan());
        assertEquals("contract.resolve", decision.plan().get(0).capabilityId());
        assertEquals("物业服务合同", decision.plan().get(0).args().get("query"));
    }

    @Test
    void queryTicketStillRoutesToQuery() {
        RouteDecision decision = router.route("查询工单");
        assertEquals("property.ticket.query", decision.capabilityId());
    }

    @Test
    void myPendingTicketsRoutesToQuery() {
        RouteDecision decision = router.route("我有哪些待处理工单？");
        assertEquals("property.ticket.query", decision.capabilityId());
    }

    @Test
    void parcelQueryRoutesToGisAgent() {
        RouteDecision decision = router.route("查询地块 P-2026-001");
        assertEquals("gis.parcel.query", decision.capabilityId());
        assertEquals("P-2026-001", decision.args().get("parcelNo"));
    }

    @Test
    void changePatchesRouteToGisAgent() {
        RouteDecision decision = router.route("有哪些遥感变化图斑？");
        assertEquals("gis.change.query", decision.capabilityId());
    }

    @Test
    void patrolCreateRoutesToGisAgent() {
        RouteDecision decision = router.route("为地块 P-2026-001 创建巡查任务");
        assertEquals("gis.patrol.create", decision.capabilityId());
        assertEquals("P-2026-001", decision.args().get("parcelNo"));
    }

    @Test
    void agentSpecificCapabilityQueryUsesCatalogFilter() {
        RouteDecision decision = router.route("检查时空资产agent目前有什么功能");
        assertEquals("platform.catalog", decision.capabilityId());
        assertEquals("geo-asset-agent", decision.args().get("agentKey"));
    }

    @Test
    void capabilityFollowUpUsesCatalogAndContextResolver() {
        RouteDecision decision = router.route("具体是什么功能");
        assertEquals("platform.catalog", decision.capabilityId());
        assertTrue(decision.args().isEmpty());
    }

    @Test
    void naturalLanguageContractReviewPlansResolveFirst() {
        RouteDecision decision = router.route("帮我审一下物业服务合同");
        assertEquals("contract.review", decision.capabilityId());
        assertEquals("contract.resolve", decision.plan().get(0).capabilityId());
        assertEquals("contract.review", decision.plan().get(1).capabilityId());
    }

    @Test
    void naturalLanguageParcelQueryUsesKeyword() {
        RouteDecision decision = router.route("江北新区 A-01 那个商业地块现在什么状态");
        assertEquals("gis.parcel.query", decision.capabilityId());
        assertTrue(String.valueOf(decision.args().get("keyword")).contains("A-01"));
    }

    @Test
    void naturalLanguageChangeQueryUsesKeyword() {
        RouteDecision decision = router.route("最近有没有比较紧急的遥感变化图斑");
        assertEquals("gis.change.query", decision.capabilityId());
        assertTrue(String.valueOf(decision.args().get("keyword")).contains("紧急"));
    }

    @Test
    void naturalLanguagePatrolPlansParcelLookup() {
        RouteDecision decision = router.route("安排人去江北 A-01 地块做一次变化图斑复核");
        assertEquals("gis.patrol.create", decision.capabilityId());
        assertEquals("gis.parcel.query", decision.plan().get(0).capabilityId());
        assertEquals("gis.patrol.create", decision.plan().get(1).capabilityId());
    }

    @Test
    void naturalLanguageTicketActionWithoutCodeAsksForSemanticResolution() {
        RouteDecision decision = router.route("把1栋大堂照明故障那个工单关掉");
        assertEquals("property.ticket.close", decision.capabilityId());
        assertTrue(decision.needClarification());
        assertTrue(decision.missingFields().contains("ticketNo"));
    }

    @Test
    void naturalLanguageAssignPlansTicketLookupAndMapsWorkerName() {
        RouteDecision decision = router.route("把3栋2单元水管漏水那个工单派给张师傅");
        assertEquals("property.ticket.assign", decision.capabilityId());
        assertEquals("10002", decision.args().get("assigneeId"));
        assertEquals("property.ticket.query", decision.plan().get(0).capabilityId());
        assertEquals("property.ticket.assign", decision.plan().get(1).capabilityId());
    }
}
