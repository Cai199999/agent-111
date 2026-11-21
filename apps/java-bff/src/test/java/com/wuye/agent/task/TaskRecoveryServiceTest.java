package com.wuye.agent.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.wuye.agent.agent.adapter.AdapterRegistry;
import com.wuye.agent.agent.model.AgentError;
import com.wuye.agent.agent.model.Capability;
import com.wuye.agent.agent.model.TaskStatus;
import com.wuye.agent.human.HumanInterventionService;
import com.wuye.agent.routing.LlmRouterClient;
import com.wuye.agent.routing.RecoveryDecision;
import com.wuye.agent.store.TaskRecord;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TaskRecoveryServiceTest {

    private TaskService taskService;
    private LlmRouterClient llm;
    private HumanInterventionService human;
    private TaskRecoveryService service;
    private Capability create;
    private Capability query;

    @BeforeEach
    void setUp() {
        taskService = mock(TaskService.class);
        AdapterRegistry registry = mock(AdapterRegistry.class);
        llm = mock(LlmRouterClient.class);
        human = mock(HumanInterventionService.class);
        service = new TaskRecoveryService(taskService, registry, llm,
                new AgentErrorTranslator(), human);

        create = new Capability("property.ticket.create", "property-maintenance",
                "ticket.create", "property", "创建工单", "创建工单",
                false, true, List.of("SYNC"), true);
        query = new Capability("property.ticket.query", "property-maintenance",
                "ticket.query", "property", "查询工单", "查询工单",
                true, false, List.of("SYNC"), true);
    }

    @Test
    void validationWithLlmClarificationKeepsPendingContext() {
        TaskRecord failed = task(query, TaskStatus.FAILED,
                AgentError.validation("缺少必填字段：ticketTypeCode"));
        when(llm.recover(anyString(), anyString(), anyMap(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(new RecoveryDecision("CLARIFY", "property.ticket.create",
                        Map.of("description", "水管漏水"), "请问是哪个楼栋和房间？",
                        List.of("locationText"), "缺少位置")));

        RecoveryOutcome outcome = service.recover("s", "报修：水管漏水", create,
                Map.of("description", "水管漏水"), failed, "10001", "trc");

        assertTrue(outcome.clarification());
        assertEquals("请问是哪个楼栋和房间？", outcome.message());
        assertEquals("property.ticket.create", outcome.pendingCapabilityId());
        assertEquals("水管漏水", outcome.pendingArgs().get("description"));
        verify(human).autoResolve("wt_1", "已转为追问用户，等待补充信息");
    }

    @Test
    void validationWithoutLlmStillAsksUserWithFriendlyFieldName() {
        TaskRecord failed = task(create, TaskStatus.FAILED,
                AgentError.validation("缺少必填字段：ticketTypeCode"));
        when(llm.recover(anyString(), anyString(), anyMap(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        RecoveryOutcome outcome = service.recover("s", "报修：喷泉坏了", create,
                Map.of("description", "喷泉坏了"), failed, "10001", "trc");

        assertTrue(outcome.clarification());
        assertTrue(outcome.message().contains("工单类型"), outcome.message());
        assertTrue(!outcome.message().contains("ticketTypeCode"), outcome.message());
    }

    @Test
    void readOnlyDependencyRetriesThenRecovers() {
        TaskRecord failed = task(query, TaskStatus.FAILED, AgentError.dependency("子 Agent 不可用"));
        TaskRecord success = task(query, TaskStatus.SUCCEEDED, null);
        when(taskService.submit(anyString(), eq(query), anyMap(), anyString(), anyString()))
                .thenReturn(success);

        RecoveryOutcome outcome = service.recover("s", "我有哪些工单？", query,
                Map.of(), failed, "10001", "trc");

        assertEquals("RETRIED", outcome.action());
        assertEquals(TaskStatus.SUCCEEDED, outcome.task().status);
        verify(human, atLeastOnce()).autoResolve("wt_1", "已自动重试");
    }

    @Test
    void noCapabilityFallsBackWithExplicitMessage() {
        TaskRecord failed = task(create, TaskStatus.FAILED,
                new AgentError("CAPABILITY_NOT_FOUND", "NOT_FOUND", false, "能力不存在"));
        when(llm.recover(anyString(), anyString(), anyMap(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(new RecoveryDecision("NO_CAPABILITY", null,
                        Map.of(), "目前没有这个功能，我可以帮你查工单或建单。",
                        List.of(), "超出能力范围")));

        RecoveryOutcome outcome = service.recover("s", "帮我订机票", create,
                Map.of(), failed, "10001", "trc");

        assertEquals("NO_CAPABILITY", outcome.action());
        assertTrue(outcome.message().contains("没有这个功能"), outcome.message());
    }

    private static TaskRecord task(Capability capability, TaskStatus status, AgentError error) {
        TaskRecord task = new TaskRecord("wt_1", "ws_1", capability.agentKey(),
                capability.capabilityId(), "client-1", "10001", "SYNC",
                Map.of("description", "x"), Instant.now());
        task.status = status;
        task.error = error;
        return task;
    }
}
