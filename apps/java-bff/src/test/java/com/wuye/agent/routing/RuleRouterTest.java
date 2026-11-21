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
}
