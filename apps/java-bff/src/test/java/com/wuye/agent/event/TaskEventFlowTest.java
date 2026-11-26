package com.wuye.agent.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wuye.agent.agent.adapter.AdapterRegistry;
import com.wuye.agent.agent.model.Capability;
import com.wuye.agent.agent.model.Decision;
import com.wuye.agent.agent.model.TaskStatus;
import com.wuye.agent.flywheel.FlywheelSample;
import com.wuye.agent.flywheel.FlywheelSampleService;
import com.wuye.agent.store.TaskRecord;
import com.wuye.agent.task.TaskService;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 任务生命周期事件契约：requested → confirmation.requested → completed/failed；
 * 并验证飞轮消费者能把终态样本落盘，且事件载荷不含确认令牌。
 */
@SpringBootTest(properties = "wuye.flywheel.root=target/test-flywheel-event")
class TaskEventFlowTest {

    @Autowired
    TaskService taskService;

    @Autowired
    AdapterRegistry registry;

    @Autowired
    EventBus eventBus;

    @Autowired
    FlywheelSampleService flywheel;

    @Test
    void approvedTaskPublishesRequestedConfirmationAndCompleted() {
        TaskRecord task = submitTicket("1栋大堂的灯不亮了");

        assertEquals(TaskStatus.WAITING_CONFIRM, task.status);
        List<EventEnvelope> waiting = events(task.taskNo);
        assertTrue(types(waiting).contains(EventTypes.TASK_REQUESTED), types(waiting).toString());
        assertTrue(types(waiting).contains(EventTypes.TASK_CONFIRMATION_REQUESTED), types(waiting).toString());
        assertTrue(waiting.stream().noneMatch(e ->
                        String.valueOf(e.payload()).contains(task.confirmation.token())),
                "确认令牌不得进入事件载荷");

        TaskRecord done = taskService.confirm(task.taskNo, Decision.APPROVE, "同意",
                "10001", false, "trc-event-approve");
        assertEquals(TaskStatus.SUCCEEDED, done.status);

        List<EventEnvelope> terminal = events(task.taskNo);
        assertTrue(types(terminal).contains(EventTypes.TASK_COMPLETED), types(terminal).toString());
        EventEnvelope completed = terminal.stream()
                .filter(e -> EventTypes.TASK_COMPLETED.equals(e.eventType()))
                .findFirst().orElseThrow();
        assertEquals(task.taskNo, completed.subject());
        assertEquals(EventTypes.TASK_COMPLETED + ":" + task.taskNo, completed.idempotencyKey());
        assertEquals("10001", completed.actorId());

        FlywheelSample sample = flywheel.recent(50).stream()
                .filter(item -> task.taskNo.equals(item.taskNo()))
                .findFirst().orElseThrow();
        assertEquals("SUCCEEDED", sample.status());
        assertTrue(sample.durationMs() >= 0);
        assertEquals("GD202611200001", ((Map<?, ?>) sample.output()).get("ticketNo"));
    }

    @Test
    void rejectedTaskPublishesFailedEvent() {
        TaskRecord task = submitTicket("空调不制冷");

        TaskRecord rejected = taskService.confirm(task.taskNo, Decision.REJECT, "不处理",
                "10001", false, "trc-event-reject");
        assertEquals(TaskStatus.REJECTED, rejected.status);

        List<EventEnvelope> events = events(task.taskNo);
        assertTrue(types(events).contains(EventTypes.TASK_FAILED), types(events).toString());
        EventEnvelope failed = events.stream()
                .filter(e -> EventTypes.TASK_FAILED.equals(e.eventType()))
                .findFirst().orElseThrow();
        assertEquals("REJECTED", failed.payload().get("status"));
        // 用户拒绝属于业务终态，不一定产生技术错误；载荷必须带耗时与输入。
        assertTrue(failed.payload().containsKey("durationMs"), failed.payload().toString());
        assertTrue(failed.payload().containsKey("input"), failed.payload().toString());
    }

    private TaskRecord submitTicket(String description) {
        Capability capability = registry.capability("property.ticket.create").orElseThrow();
        return taskService.submit("ws_event_" + System.nanoTime(), capability,
                Map.of("ticketTypeCode", "LIGHTING",
                        "description", description,
                        "locationText", "1栋大堂"),
                "10001", "trc-event-submit");
    }

    private List<EventEnvelope> events(String taskNo) {
        return eventBus.recent(500).stream()
                .filter(event -> taskNo.equals(event.subject()))
                .toList();
    }

    private List<String> types(List<EventEnvelope> events) {
        return events.stream().map(EventEnvelope::eventType).toList();
    }
}
