package com.wuye.agent.agent.adapter;

import com.wuye.agent.agent.model.AdapterResult;
import com.wuye.agent.agent.model.AdapterTaskRequest;
import com.wuye.agent.agent.model.AgentDescriptor;
import com.wuye.agent.agent.model.AgentError;
import com.wuye.agent.agent.model.AgentHealth;
import com.wuye.agent.agent.model.Capability;
import com.wuye.agent.agent.model.Confirmation;
import com.wuye.agent.agent.model.Decision;
import com.wuye.agent.agent.model.TaskEvent;
import com.wuye.agent.agent.model.TaskStatus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 物业子 Agent 的 Mock Adapter（方言 property-v08）。
 *
 * <p>刻意模拟物业的 {@code {code,data}} 包络、数字错误码、token-only 确认体，
 * 验证主 Agent 的适配层确实能吸收方言差异。生产化时替换为 HTTP 实现，
 * 对上层接口不变。</p>
 */
@Component
@ConditionalOnProperty(name = "wuye.agent.mode", havingValue = "mock", matchIfMissing = true)
public class MockPropertyAdapter implements AgentAdapter {

    private static final String AGENT_KEY = "property-maintenance";
    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final AtomicLong childSeq = new AtomicLong();

    private final AgentDescriptor descriptor = new AgentDescriptor(
            AGENT_KEY,
            "物业运维 Agent（Mock）",
            "property-v08",
            "0.8.0",
            "wrapped",
            "/agent/v1/health",
            "/agent/v1/ready",
            true,
            "db-recovery",
            List.of(
                    capability("property.ticket.query", "ticket.query", "查询工单", true, false),
                    capability("property.ticket.detail", "ticket.detail", "工单详情", true, false),
                    capability("property.knowledge.search", "knowledge.search", "物业知识检索", true, false),
                    capability("property.ticket.create", "ticket.create", "创建工单", false, true),
                    capability("property.ticket.assign", "ticket.assign", "派单", false, true)
            ));

    private static Capability capability(String id, String taskType, String name,
                                         boolean readOnly, boolean confirm) {
        return new Capability(id, AGENT_KEY, taskType, "property", name, name,
                readOnly, confirm, List.of("SYNC"), true);
    }

    @Override
    public AgentDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public AdapterResult submit(AdapterTaskRequest request) {
        String childTaskId = "tsk_p_" + childSeq.incrementAndGet();
        State state = new State(childTaskId);
        state.capabilityId = request.capabilityId();
        state.input = request.input() == null ? Map.of() : request.input();
        states.put(childTaskId, state);

        switch (request.capabilityId()) {
            case "property.ticket.query" -> {
                Object data = List.of(
                        ticket("GD20261119000107", "3栋2单元水管漏水", "PENDING", "HIGH", "未派单"),
                        ticket("GD20261119000108", "1栋大堂照明故障", "ASSIGNED", "NORMAL", "10002"),
                        ticket("GD20261119000109", "地下车库道闸异常", "PENDING", "LOW", "未派单")
                );
                return succeed(state, unwrapProperty(wrapProperty(data)));
            }
            case "property.ticket.detail" -> {
                String ticketNo = string(request.input(), "ticketNo", "GD20261119000107");
                Object data = ticket(ticketNo, "3栋2单元水管漏水", "PENDING", "HIGH", "未派单");
                return succeed(state, unwrapProperty(wrapProperty(data)));
            }
            case "property.knowledge.search" -> {
                Object data = List.of(
                        Map.of("title", "消防通道管理规定", "snippet", "消防通道不得堆放杂物，物业应每日巡查。", "score", 0.93),
                        Map.of("title", "二次供水巡检制度", "snippet", "二次供水设备每季度清洗消毒一次。", "score", 0.88)
                );
                return succeed(state, unwrapProperty(wrapProperty(data)));
            }
            case "property.ticket.create" -> {
                String description = string(request.input(), "description", "用户报修");
                Confirmation confirmation = confirmation(
                        "建单确认",
                        "HIGH",
                        List.of(
                                new Confirmation.Field("报修内容", description),
                                new Confirmation.Field("位置", string(request.input(), "locationText", "待补充"))
                        ));
                state.confirmation = confirmation;
                state.status = TaskStatus.WAITING_CONFIRM;
                state.currentStep = "WAITING_CONFIRM";
                event(state, "STATUS_CHANGED", TaskStatus.WAITING_CONFIRM, Map.of("message", "等待用户确认建单"));
                return AdapterResult.waiting(childTaskId, confirmation, List.copyOf(state.events));
            }
            case "property.ticket.assign" -> {
                String ticketNo = string(request.input(), "ticketNo", "GD20261119000107");
                String assigneeId = string(request.input(), "assigneeId", "10002");
                Confirmation confirmation = confirmation(
                        "派单确认",
                        "HIGH",
                        List.of(
                                new Confirmation.Field("工单", ticketNo),
                                new Confirmation.Field("处理人", assigneeId)
                        ));
                state.confirmation = confirmation;
                state.status = TaskStatus.WAITING_CONFIRM;
                state.currentStep = "WAITING_CONFIRM";
                event(state, "STATUS_CHANGED", TaskStatus.WAITING_CONFIRM, Map.of("message", "等待用户确认派单"));
                return AdapterResult.waiting(childTaskId, confirmation, List.copyOf(state.events));
            }
            default -> {
                AgentError error = new AgentError("60001", "VALIDATION", false, "未实现的物业能力：" + request.capabilityId());
                state.status = TaskStatus.FAILED;
                state.error = error;
                event(state, "ERROR", TaskStatus.FAILED, Map.of("message", error.message()));
                return AdapterResult.failed(childTaskId, error, List.copyOf(state.events));
            }
        }
    }

    @Override
    public AdapterResult getTask(String childTaskId) {
        State state = states.get(childTaskId);
        return state == null ? null : snapshot(state);
    }

    @Override
    public AdapterResult confirm(String childTaskId, String token, Decision decision, String comment) {
        State state = states.get(childTaskId);
        if (state == null) {
            return AdapterResult.failed(childTaskId, new AgentError("20001", "NOT_FOUND", false, "任务不存在"), List.of());
        }
        if (state.confirmation == null) {
            return AdapterResult.failed(childTaskId, new AgentError("20002", "CONFLICT", false, "任务无需确认"), List.of());
        }
        if (state.confirmation.expired()) {
            state.status = TaskStatus.FAILED;
            state.error = AgentError.timeout("确认令牌已过期");
            state.confirmation = state.confirmation.withStatus("EXPIRED");
            event(state, "ERROR", TaskStatus.FAILED, Map.of("message", "确认令牌已过期"));
            return snapshot(state);
        }
        if (!state.confirmation.token().equals(token)) {
            return AdapterResult.failed(childTaskId, new AgentError("10003", "AUTH", false, "确认令牌无效"), List.of());
        }

        if (decision == Decision.APPROVE) {
            state.status = TaskStatus.SUCCEEDED;
            state.output = "property.ticket.create".equals(state.capabilityId)
                    ? Map.of("ticketNo", "GD202611200001", "status", "PENDING")
                    : Map.of("ticketNo", string(state.input, "ticketNo", "GD20261119000107"),
                    "assigneeId", string(state.input, "assigneeId", "10002"), "status", "ASSIGNED");
            state.progress = 100;
            state.currentStep = "DONE";
            state.confirmation = state.confirmation.withStatus("APPROVED");
            event(state, "STATUS_CHANGED", TaskStatus.SUCCEEDED, Map.of("message", "确认通过，执行成功"));
            event(state, "DONE", TaskStatus.SUCCEEDED, Map.of());
        } else {
            state.status = TaskStatus.REJECTED;
            state.currentStep = "REJECTED";
            state.confirmation = state.confirmation.withStatus("REJECTED");
            event(state, "STATUS_CHANGED", TaskStatus.REJECTED, Map.of("message", "用户拒绝"));
        }
        return snapshot(state);
    }

    @Override
    public AdapterResult cancel(String childTaskId, String reason) {
        State state = states.get(childTaskId);
        if (state == null) {
            return AdapterResult.failed(childTaskId, new AgentError("20001", "NOT_FOUND", false, "任务不存在"), List.of());
        }
        if (state.status.terminal()) {
            return AdapterResult.failed(childTaskId, new AgentError("20002", "CONFLICT", false, "任务已终态"), List.of());
        }
        state.status = TaskStatus.CANCELLED;
        state.currentStep = "CANCELLED";
        if (state.confirmation != null) {
            state.confirmation = state.confirmation.withStatus("CANCELLED");
        }
        event(state, "STATUS_CHANGED", TaskStatus.CANCELLED, Map.of("reason", reason == null ? "" : reason));
        return snapshot(state);
    }

    @Override
    public List<TaskEvent> events(String childTaskId, long afterSeq) {
        State state = states.get(childTaskId);
        if (state == null) {
            return List.of();
        }
        return state.events.stream().filter(e -> e.seq() > afterSeq).toList();
    }

    @Override
    public AgentHealth health() {
        return new AgentHealth(AGENT_KEY, "UP", "UP", 2);
    }

    private AdapterResult succeed(State state, Object output) {
        state.status = TaskStatus.SUCCEEDED;
        state.output = output;
        state.progress = 100;
        state.currentStep = "DONE";
        event(state, "STATUS_CHANGED", TaskStatus.SUCCEEDED, Map.of("message", "查询成功"));
        event(state, "DONE", TaskStatus.SUCCEEDED, Map.of());
        return snapshot(state);
    }

    private AdapterResult snapshot(State state) {
        return new AdapterResult(state.childTaskId, state.status, state.output, state.error,
                state.confirmation, state.progress, state.currentStep, true, List.copyOf(state.events));
    }

    private void event(State state, String type, TaskStatus status, Map<String, Object> payload) {
        state.events.add(new TaskEvent(state.seq.incrementAndGet(), type, status,
                payload == null ? Map.of() : payload, Instant.now()));
    }

    private Confirmation confirmation(String title, String risk, List<Confirmation.Field> fields) {
        return new Confirmation(UUID.randomUUID().toString(), title, risk, fields,
                Instant.now().plus(10, ChronoUnit.MINUTES), "PENDING");
    }

    private static Map<String, Object> ticket(String no, String title, String status,
                                              String priority, String assignee) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ticketNo", no);
        map.put("title", title);
        map.put("status", status);
        map.put("priority", priority);
        map.put("assignee", assignee);
        return map;
    }

    /** 模拟物业的 {code,data} 包络。 */
    private static Map<String, Object> wrapProperty(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 0);
        body.put("data", data);
        return body;
    }

    /** Adapter 内部解包，业务层只见 data。 */
    private static Object unwrapProperty(Object body) {
        if (body instanceof Map<?, ?> map && map.containsKey("data")) {
            return map.get("data");
        }
        return body;
    }

    private static String string(Map<String, Object> input, String key, String fallback) {
        Object value = input == null ? null : input.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static final class State {
        final String childTaskId;
        final AtomicLong seq = new AtomicLong();
        final List<TaskEvent> events = new CopyOnWriteArrayList<>();
        volatile TaskStatus status = TaskStatus.PENDING;
        volatile Object output;
        volatile AgentError error;
        volatile Confirmation confirmation;
        volatile int progress;
        volatile String currentStep = "PENDING";
        volatile String capabilityId;
        volatile Map<String, Object> input = Map.of();

        State(String childTaskId) {
            this.childTaskId = childTaskId;
        }
    }
}
