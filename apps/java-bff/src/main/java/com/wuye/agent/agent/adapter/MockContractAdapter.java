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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

/**
 * 合同子 Agent 的 Mock Adapter（方言 contract-v07）。
 *
 * <p>模拟合同侧的裸对象响应、字符串错误码、异步 202 任务、协作式取消，
 * 以及“拒绝 -> FAILED(CONFIRM_REJECTED)”的方言，验证主 Agent 的归一化。</p>
 */
@Component
public class MockContractAdapter implements AgentAdapter {

    private static final String AGENT_KEY = "contract-review";
    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final AtomicLong childSeq = new AtomicLong();
    private final ScheduledExecutorService worker = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "mock-contract-worker");
        t.setDaemon(true);
        return t;
    });

    private final AgentDescriptor descriptor = new AgentDescriptor(
            AGENT_KEY,
            "合同审查 Agent（Mock）",
            "contract-v07",
            "0.7.0",
            "raw",
            "/health",
            "/ready",
            true,
            "startup-recovery",
            List.of(
                    capability("contract.rule.list", "contract.rule.list", "查询审查规则", true, false, List.of("SYNC"), true),
                    capability("contract.knowledge.search", "knowledge.search", "合同知识检索", true, false, List.of("SYNC"), true),
                    capability("contract.resolve", "contract.resolve", "解析合同", true, false, List.of("SYNC"), true),
                    capability("contract.review", "contract.review", "合同审查", true, false, List.of("ASYNC"), true),
                    capability("contract.rule.create", "contract.rule.create", "创建审查规则", false, true, List.of("ASYNC"), true),
                    capability("contract.import", "contract.import", "导入合同", false, true, List.of("ASYNC"), false)
            ));

    private static Capability capability(String id, String taskType, String name,
                                         boolean readOnly, boolean confirm,
                                         List<String> modes, boolean enabled) {
        return new Capability(id, AGENT_KEY, taskType, "contract", name, name,
                readOnly, confirm, modes, enabled);
    }

    @Override
    public AgentDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public AdapterResult submit(AdapterTaskRequest request) {
        String childTaskId = "tsk_c_" + childSeq.incrementAndGet();
        State state = new State(childTaskId);
        state.capabilityId = request.capabilityId();
        state.input = request.input() == null ? Map.of() : request.input();
        states.put(childTaskId, state);

        switch (request.capabilityId()) {
            case "contract.review" -> {
                state.status = TaskStatus.PENDING;
                state.currentStep = "PENDING";
                event(state, "STATUS_CHANGED", TaskStatus.PENDING, Map.of("message", "任务已受理"));
                scheduleReview(state);
                return AdapterResult.pending(childTaskId, 0, "PENDING", List.copyOf(state.events));
            }
            case "contract.rule.list" -> {
                Object output = List.of(
                        Map.of("ruleCode", "R-CON-001", "title", "合同主体资格", "level", "MAJOR"),
                        Map.of("ruleCode", "R-CON-002", "title", "付款节点与验收", "level", "GENERAL"),
                        Map.of("ruleCode", "R-CON-003", "title", "违约责任对等", "level", "MAJOR")
                );
                return succeed(state, output, "查询成功");
            }
            case "contract.knowledge.search" -> {
                Object output = List.of(
                        Map.of("title", "物业服务合同示范文本", "snippet", "建议明确服务范围、考核标准与费用调整机制。", "score", 0.91),
                        Map.of("title", "违约责任条款要点", "snippet", "违约金比例应与实际损失相匹配。", "score", 0.87)
                );
                return succeed(state, output, "检索成功");
            }
            case "contract.resolve" -> {
                String query = string(request.input(), "query", "物业服务合同");
                Object output = Map.of(
                        "contractId", 10001,
                        "versionId", 20001,
                        "contractNo", "HT-2026-001",
                        "name", "物业服务合同",
                        "matchedBy", query
                );
                return succeed(state, output, "解析成功");
            }
            case "contract.rule.create" -> {
                String ruleName = string(request.input(), "ruleName", "新审查规则");
                Confirmation confirmation = confirmation(
                        "创建规则确认",
                        "MEDIUM",
                        List.of(
                                new Confirmation.Field("规则名称", ruleName),
                                new Confirmation.Field("适用合同", "全部合同")
                        ));
                state.confirmation = confirmation;
                state.status = TaskStatus.WAITING_CONFIRM;
                state.currentStep = "WAITING_CONFIRM";
                event(state, "STATUS_CHANGED", TaskStatus.WAITING_CONFIRM, Map.of("message", "等待用户确认创建规则"));
                return AdapterResult.waiting(childTaskId, confirmation, List.copyOf(state.events));
            }
            case "contract.import" -> {
                AgentError error = new AgentError("CAPABILITY_DISABLED", "VALIDATION", false, "合同导入能力当前未启用");
                state.status = TaskStatus.FAILED;
                state.error = error;
                event(state, "ERROR", TaskStatus.FAILED, Map.of("message", error.message()));
                return AdapterResult.failed(childTaskId, error, List.copyOf(state.events));
            }
            default -> {
                AgentError error = new AgentError("CAPABILITY_NOT_FOUND", "NOT_FOUND", false, "未知合同能力：" + request.capabilityId());
                state.status = TaskStatus.FAILED;
                state.error = error;
                event(state, "ERROR", TaskStatus.FAILED, Map.of("message", error.message()));
                return AdapterResult.failed(childTaskId, error, List.copyOf(state.events));
            }
        }
    }

    private void scheduleReview(State state) {
        worker.schedule(() -> {
            if (state.status == TaskStatus.CANCELLED) {
                return;
            }
            state.status = TaskStatus.RUNNING;
            state.currentStep = "RUNNING";
            state.progress = 30;
            event(state, "STATUS_CHANGED", TaskStatus.RUNNING, Map.of("message", "开始审查"));
            event(state, "PROGRESS", TaskStatus.RUNNING, Map.of("progress", 30, "step", "条款抽取"));
        }, 300, TimeUnit.MILLISECONDS);

        worker.schedule(() -> {
            if (state.status == TaskStatus.CANCELLED) {
                return;
            }
            Object output = new LinkedHashMap<String, Object>() {{
                put("summary", "审查完成：发现 3 个风险点");
                put("findingsCount", 3);
                put("findingsByLevel", Map.of("MAJOR", 1, "GENERAL", 2));
                put("overall", "FAIL");
            }};
            state.status = TaskStatus.SUCCEEDED;
            state.output = output;
            state.progress = 100;
            state.currentStep = "DONE";
            event(state, "TOOL_CALL", TaskStatus.RUNNING, Map.of("tool", "contract.rules.evaluate", "status", "SUCCEEDED"));
            event(state, "STATUS_CHANGED", TaskStatus.SUCCEEDED, Map.of("message", "审查完成"));
            event(state, "PROGRESS", TaskStatus.SUCCEEDED, Map.of("progress", 100));
            event(state, "DONE", TaskStatus.SUCCEEDED, Map.of());
        }, 1500, TimeUnit.MILLISECONDS);
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
            return AdapterResult.failed(childTaskId, new AgentError("TASK_NOT_FOUND", "NOT_FOUND", false, "任务不存在"), List.of());
        }
        if (state.confirmation == null) {
            return AdapterResult.failed(childTaskId, new AgentError("TASK_STATE_CONFLICT", "CONFLICT", false, "任务无需确认"), List.of());
        }
        if (state.confirmation.expired()) {
            state.status = TaskStatus.FAILED;
            state.error = AgentError.timeout("确认令牌已过期");
            state.confirmation = state.confirmation.withStatus("EXPIRED");
            event(state, "ERROR", TaskStatus.FAILED, Map.of("message", "确认令牌已过期"));
            return snapshot(state);
        }
        if (!state.confirmation.token().equals(token)) {
            return AdapterResult.failed(childTaskId, new AgentError("AUTH_FORBIDDEN", "AUTH", false, "确认令牌无效"), List.of());
        }

        if (decision == Decision.APPROVE) {
            state.confirmation = state.confirmation.withStatus("APPROVED");
            state.status = TaskStatus.PENDING;
            state.currentStep = "PENDING";
            event(state, "STATUS_CHANGED", TaskStatus.PENDING, Map.of("message", "确认通过，进入执行队列"));
            worker.schedule(() -> {
                if (state.status == TaskStatus.CANCELLED) {
                    return;
                }
                state.status = TaskStatus.SUCCEEDED;
                state.output = Map.of("ruleId", "r_1001", "status", "ENABLED");
                state.progress = 100;
                state.currentStep = "DONE";
                event(state, "STATUS_CHANGED", TaskStatus.SUCCEEDED, Map.of("message", "规则创建成功"));
                event(state, "DONE", TaskStatus.SUCCEEDED, Map.of());
            }, 600, TimeUnit.MILLISECONDS);
        } else {
            state.status = TaskStatus.FAILED;
            state.error = new AgentError("CONFIRM_REJECTED", "USER_REJECTED", false, "用户拒绝");
            state.currentStep = "REJECTED";
            state.confirmation = state.confirmation.withStatus("REJECTED");
            event(state, "STATUS_CHANGED", TaskStatus.FAILED, Map.of("message", "用户拒绝"));
        }
        return snapshot(state);
    }

    @Override
    public AdapterResult cancel(String childTaskId, String reason) {
        State state = states.get(childTaskId);
        if (state == null) {
            return AdapterResult.failed(childTaskId, new AgentError("TASK_NOT_FOUND", "NOT_FOUND", false, "任务不存在"), List.of());
        }
        if (state.status.terminal()) {
            return AdapterResult.failed(childTaskId, new AgentError("TASK_STATE_CONFLICT", "CONFLICT", false, "任务已终态"), List.of());
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
        return state == null ? List.of() : state.events.stream().filter(e -> e.seq() > afterSeq).toList();
    }

    @Override
    public AgentHealth health() {
        return new AgentHealth(AGENT_KEY, "UP", "UP", 3);
    }

    private AdapterResult succeed(State state, Object output, String message) {
        state.status = TaskStatus.SUCCEEDED;
        state.output = output;
        state.progress = 100;
        state.currentStep = "DONE";
        event(state, "STATUS_CHANGED", TaskStatus.SUCCEEDED, Map.of("message", message));
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
