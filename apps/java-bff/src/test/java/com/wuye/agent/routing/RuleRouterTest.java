package com.wuye.agent.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
