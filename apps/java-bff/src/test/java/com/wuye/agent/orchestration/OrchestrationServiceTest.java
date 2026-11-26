package com.wuye.agent.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.wuye.agent.agent.adapter.AdapterRegistry;
import com.wuye.agent.agent.model.Capability;
import com.wuye.agent.agent.model.TaskStatus;
import com.wuye.agent.auth.AuthUser;
import com.wuye.agent.auth.PolicyService;
import com.wuye.agent.routing.AiProperties;
import com.wuye.agent.routing.RouteDecision;
import com.wuye.agent.store.TaskRecord;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 编排接入层：计划校验、权限裁剪、执行后验收、失败回退。
 */
class OrchestrationServiceTest {

    private FakeGateway gateway;
    private OrchestrationService service;
    private AuthUser user;
    private Capability capability;

    @BeforeEach
    void setUp() {
        gateway = new FakeGateway();
        AdapterRegistry registry = mock(AdapterRegistry.class);
        PolicyService policy = mock(PolicyService.class);
        AiProperties properties = new AiProperties();
        properties.setEnabled(true);
        properties.setOrchestrationEnabled(true);

        service = new OrchestrationService(gateway, registry, policy, properties);
        user = new AuthUser(1, "U1", "admin", "管理员", "10001", "0",
                Set.of("ADMIN"), Set.of());
        capability = new Capability("property.ticket.create", "property-maintenance",
                "ticket.create", "property", "创建工单", "创建工单",
                false, true, List.of("SYNC"), true);
        when(registry.capabilities()).thenReturn(List.of(capability));
        when(policy.allowedCapabilities(user, List.of(capability))).thenReturn(List.of(capability));
    }

    @Test
    void reviewPlanSendsOnlyAllowedCapabilitiesAndUsesReturnedPlan() {
        RouteDecision.PlanStep reviewed = new RouteDecision.PlanStep(
                "property.ticket.create", Map.of("ticketTypeCode", "LIGHTING"));
        gateway.planResult = Optional.of(new OrchestrationResult(
                "DISPATCH_REQUIRED", null, List.of(reviewed), List.of(), List.of("steps=1")));

        Optional<OrchestrationOutcome> result = service.reviewPlan(
                new RouteDecision("property.ticket.create", Map.of("description", "灯坏了"),
                        0.9, false, null, "test", List.of(), List.of(), "LLM"),
                "1栋大堂灯不亮", user, "ws_1", "trc_1");

        assertTrue(result.isPresent());
        assertEquals("DISPATCH_REQUIRED", result.get().verdict());
        assertTrue(result.get().dispatchRequired());
        assertEquals(List.of(reviewed), result.get().plan());
        assertEquals(List.of(capability), gateway.lastPlan.capabilities());
        assertEquals("10001", gateway.lastPlan.actorId());
        assertEquals("0", gateway.lastPlan.tenantId());
    }

    @Test
    void blockingPlanDoesNotExecute() {
        gateway.planResult = Optional.of(new OrchestrationResult(
                "NEEDS_INPUT", "请补充请假开始时间", List.of(), List.of(), List.of()));

        Optional<OrchestrationOutcome> result = service.reviewPlan(
                new RouteDecision("property.ticket.create", Map.of(), 0.9, false,
                        null, "test", List.of(), List.of(), "LLM"),
                "帮我处理", user, "ws_1", "trc_1");

        assertTrue(result.isPresent());
        assertTrue(result.get().blocking());
        assertEquals("请补充请假开始时间", result.get().clarification());
    }

    @Test
    void disabledOrchestrationSkipsGateway() {
        AiProperties disabled = new AiProperties();
        disabled.setEnabled(true);
        disabled.setOrchestrationEnabled(false);
        OrchestrationService off = new OrchestrationService(
                gateway, mock(AdapterRegistry.class), mock(PolicyService.class), disabled);

        Optional<OrchestrationOutcome> result = off.reviewPlan(
                new RouteDecision("property.ticket.create", Map.of(), 0.9, false,
                        null, "test", List.of(), List.of(), "LLM"),
                "帮我处理", user, "ws_1", "trc_1");

        assertTrue(result.isEmpty());
        assertEquals(null, gateway.lastPlan);
    }

    @Test
    void verifyTerminalTaskSendsResultForAcceptance() {
        gateway.verifyResult = Optional.of(new OrchestrationResult(
                "NEEDS_INPUT", "还需要补充：ticketNo", List.of(), List.of(), List.of()));
        TaskRecord task = new TaskRecord("wt_1", "ws_1", "property-maintenance",
                "property.ticket.create", "client_1", "10001", "SYNC",
                Map.of("description", "灯坏了"), Instant.now());
        task.status = TaskStatus.SUCCEEDED;
        task.output = Map.of("status", "CREATED");
        task.finishedAt = Instant.now();

        Optional<OrchestrationOutcome> result = service.verify(
                capability, task, List.of(new RouteDecision.PlanStep(
                        "property.ticket.create", Map.of())), "ws_1", "trc_1");

        assertTrue(result.isPresent());
        assertEquals("NEEDS_INPUT", result.get().verdict());
        assertTrue(result.get().blocking());
        assertEquals(1, gateway.lastVerify.steps().size());
        assertEquals("SUCCEEDED", gateway.lastVerify.steps().get(0).status());
        assertEquals("property.ticket.create", gateway.lastVerify.steps().get(0).capabilityId());
    }

    @Test
    void verifyMultipleStepsSendsEveryStep() {
        gateway.verifyResult = Optional.of(new OrchestrationResult(
                "FAILED", "执行失败：property.ticket.create: boom", List.of(), List.of(), List.of()));
        List<OrchestrationResult.StepResult> steps = List.of(
                new OrchestrationResult.StepResult("property.profile.get", "property-maintenance",
                        "SUCCEEDED", Map.of("contactName", "张三"), null, List.of()),
                new OrchestrationResult.StepResult("property.ticket.create", "property-maintenance",
                        "FAILED", null, "boom", List.of()));

        Optional<OrchestrationOutcome> result = service.verify(
                steps, List.of(), "ws_1", "trc_1");

        assertTrue(result.isPresent());
        assertEquals("FAILED", result.get().verdict());
        assertEquals(2, gateway.lastVerify.steps().size());
        assertEquals(steps, result.get().steps());
    }

    @Test
    void nonTerminalTaskIsNotVerified() {
        TaskRecord task = new TaskRecord("wt_1", "ws_1", "property-maintenance",
                "property.ticket.create", "client_1", "10001", "SYNC",
                Map.of(), Instant.now());
        task.status = TaskStatus.WAITING_CONFIRM;

        Optional<OrchestrationOutcome> result = service.verify(
                capability, task, List.of(), "ws_1", "trc_1");

        assertTrue(result.isEmpty());
        assertFalse(gateway.verifyCalled);
    }

    private static final class FakeGateway implements OrchestrationGateway {
        private Optional<OrchestrationResult> planResult = Optional.empty();
        private Optional<OrchestrationResult> verifyResult = Optional.empty();
        private PlanRequest lastPlan;
        private VerifyRequest lastVerify;
        private boolean verifyCalled;

        @Override
        public Optional<OrchestrationResult> plan(PlanRequest request) {
            this.lastPlan = request;
            return planResult;
        }

        @Override
        public Optional<OrchestrationResult> verify(VerifyRequest request) {
            this.lastVerify = request;
            this.verifyCalled = true;
            return verifyResult;
        }
    }
}
