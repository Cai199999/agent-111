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

/**
 * 运行时安装的“完整示例 Agent”：巡检报告 Agent。
 *
 * <p>它不是 Spring Bean，由 {@code AgentInstallService} 在管理员点击安装时创建并注册。
 * 能力覆盖：只读查询、写操作确认、异步任务、进度事件、取消、健康检查，
 * 用来证明平台不仅能接第三个 Agent，还能接“完整生命周期”的 Agent。</p>
 */
public class DynamicInspectionAgent implements AgentAdapter, AutoCloseable {

    public static final String AGENT_KEY = "inspection-agent";

    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final AtomicLong childSeq = new AtomicLong();
    private final ScheduledExecutorService worker = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "dynamic-inspection-worker");
        thread.setDaemon(true);
        return thread;
    });

    private final AgentDescriptor descriptor = new AgentDescriptor(
            AGENT_KEY,
            "巡检报告 Agent（运行时安装）",
            "canonical-v1",
            "1.0.0",
            "raw",
            "/health",
            "/ready",
            true,
            "process",
            List.of(
                    capability("inspect.report.query", "report.query", "查询巡检报告", true, false, "SYNC"),
                    capability("inspect.plan.create", "plan.create", "创建巡检计划", false, true, "SYNC"),
                    capability("inspect.report.generate", "report.generate", "生成巡检报告", false, true, "ASYNC"),
                    capability("inspect.report.export", "report.export", "导出巡检报告", true, false, "ASYNC")
            ));

    private static Capability capability(String id, String taskType, String name,
                                         boolean readOnly, boolean confirm, String mode) {
        return new Capability(id, AGENT_KEY, taskType, "inspect", name, name,
                readOnly, confirm, List.of(mode), true);
    }

    @Override
    public AgentDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public AdapterResult submit(AdapterTaskRequest request) {
        String childTaskId = "tsk_i_" + childSeq.incrementAndGet();
        State state = new State(childTaskId);
        state.capabilityId = request.capabilityId();
        state.input = request.input() == null ? Map.of() : request.input();
        state.actorId = request.actorId();
        states.put(childTaskId, state);

        switch (request.capabilityId()) {
            case "inspect.report.query" -> {
                return succeed(state, List.of(
                        report("IR-2026-001", "周度巡检", "江北新区", "2026-11-20", "已完成", 12),
                        report("IR-2026-002", "专项巡检", "城南片区", "2026-11-18", "已完成", 5),
                        report("IR-2026-003", "月度巡检", "全部区域", "2026-11-15", "生成中", 0)
                ));
            }
            case "inspect.plan.create" -> {
                Confirmation confirmation = confirmation("巡检计划确认", "HIGH", List.of(
                        new Confirmation.Field("区域", string(request.input(), "region", "全部区域")),
                        new Confirmation.Field("计划名称", string(request.input(), "planName", "周度巡检计划")),
                        new Confirmation.Field("截止时间", string(request.input(), "deadline", "2026-11-25"))
                ));
                return waiting(state, confirmation, "等待用户确认巡检计划");
            }
            case "inspect.report.generate" -> {
                Confirmation confirmation = confirmation("巡检报告生成确认", "HIGH", List.of(
                        new Confirmation.Field("报告类型",
                                reportTypeLabel(string(request.input(), "reportType", "周报"))),
                        new Confirmation.Field("区域", string(request.input(), "region", "全部区域")),
                        new Confirmation.Field("统计周期",
                                periodLabel(string(request.input(), "period", "最近 7 天")))
                ));
                return waiting(state, confirmation, "等待用户确认生成巡检报告");
            }
            case "inspect.report.export" -> {
                state.status = TaskStatus.PENDING;
                state.currentStep = "PENDING";
                event(state, "STATUS_CHANGED", TaskStatus.PENDING, Map.of("message", "导出任务已受理"));
                scheduleExport(state);
                return snapshot(state);
            }
            default -> {
                AgentError error = new AgentError("CAPABILITY_NOT_FOUND", "NOT_FOUND", false,
                        "未实现的巡检能力：" + request.capabilityId());
                state.status = TaskStatus.FAILED;
                state.error = error;
                event(state, "ERROR", TaskStatus.FAILED, Map.of("message", error.message()));
                return snapshot(state);
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
            return AdapterResult.failed(childTaskId,
                    new AgentError("TASK_NOT_FOUND", "NOT_FOUND", false, "任务不存在"), List.of());
        }
        if (state.confirmation == null) {
            return AdapterResult.failed(childTaskId,
                    new AgentError("TASK_STATE_CONFLICT", "CONFLICT", false, "任务无需确认"), List.of());
        }
        if (state.confirmation.expired()) {
            state.status = TaskStatus.FAILED;
            state.error = AgentError.timeout("确认令牌已过期");
            state.confirmation = state.confirmation.withStatus("EXPIRED");
            event(state, "ERROR", TaskStatus.FAILED, Map.of("message", "确认令牌已过期"));
            return snapshot(state);
        }
        if (!state.confirmation.token().equals(token)) {
            return AdapterResult.failed(childTaskId,
                    new AgentError("AUTH_FORBIDDEN", "AUTH", false, "确认令牌无效"), List.of());
        }

        if (decision != Decision.APPROVE) {
            state.status = TaskStatus.REJECTED;
            state.currentStep = "REJECTED";
            state.confirmation = state.confirmation.withStatus("REJECTED");
            event(state, "STATUS_CHANGED", TaskStatus.REJECTED, Map.of("message", "用户拒绝"));
            return snapshot(state);
        }

        state.confirmation = state.confirmation.withStatus("APPROVED");
        if ("inspect.plan.create".equals(state.capabilityId)) {
            state.status = TaskStatus.SUCCEEDED;
            state.output = Map.of(
                    "planNo", "IP-2026-001",
                    "planName", string(state.input, "planName", "周度巡检计划"),
                    "region", string(state.input, "region", "全部区域"),
                    "status", "CREATED");
            state.progress = 100;
            state.currentStep = "DONE";
            event(state, "STATUS_CHANGED", TaskStatus.SUCCEEDED, Map.of("message", "巡检计划已创建"));
            event(state, "DONE", TaskStatus.SUCCEEDED, Map.of());
            return snapshot(state);
        }
        if ("inspect.report.generate".equals(state.capabilityId)) {
            state.status = TaskStatus.PENDING;
            state.currentStep = "PENDING";
            event(state, "STATUS_CHANGED", TaskStatus.PENDING, Map.of("message", "报告生成任务已受理"));
            scheduleReport(state);
            return snapshot(state);
        }

        state.status = TaskStatus.SUCCEEDED;
        state.output = Map.of("status", "DONE");
        state.progress = 100;
        state.currentStep = "DONE";
        event(state, "STATUS_CHANGED", TaskStatus.SUCCEEDED, Map.of("message", "执行成功"));
        event(state, "DONE", TaskStatus.SUCCEEDED, Map.of());
        return snapshot(state);
    }

    @Override
    public AdapterResult cancel(String childTaskId, String reason) {
        State state = states.get(childTaskId);
        if (state == null) {
            return AdapterResult.failed(childTaskId,
                    new AgentError("TASK_NOT_FOUND", "NOT_FOUND", false, "任务不存在"), List.of());
        }
        if (state.status.terminal()) {
            return AdapterResult.failed(childTaskId,
                    new AgentError("TASK_STATE_CONFLICT", "CONFLICT", false, "任务已终态"), List.of());
        }
        state.status = TaskStatus.CANCELLED;
        state.currentStep = "CANCELLED";
        if (state.confirmation != null) {
            state.confirmation = state.confirmation.withStatus("CANCELLED");
        }
        event(state, "STATUS_CHANGED", TaskStatus.CANCELLED,
                Map.of("reason", reason == null ? "" : reason));
        return snapshot(state);
    }

    @Override
    public List<TaskEvent> events(String childTaskId, long afterSeq) {
        State state = states.get(childTaskId);
        return state == null ? List.of()
                : state.events.stream().filter(event -> event.seq() > afterSeq).toList();
    }

    @Override
    public AgentHealth health() {
        return new AgentHealth(AGENT_KEY, "UP", "UP", 1);
    }

    private void scheduleReport(State state) {
        worker.schedule(() -> progress(state, 30, "汇总巡检数据"), 300, TimeUnit.MILLISECONDS);
        worker.schedule(() -> progress(state, 70, "生成风险清单"), 900, TimeUnit.MILLISECONDS);
        worker.schedule(() -> {
            if (state.status == TaskStatus.CANCELLED) {
                return;
            }
            state.status = TaskStatus.SUCCEEDED;
            state.output = Map.of(
                    "reportNo", "IR-2026-001",
                    "reportType", reportTypeLabel(string(state.input, "reportType", "周报")),
                    "region", string(state.input, "region", "全部区域"),
                    "findingsCount", 12,
                    "summary", "巡检报告生成完成：3 个区域，12 个问题点",
                    "status", "DONE");
            state.progress = 100;
            state.currentStep = "DONE";
            event(state, "STATUS_CHANGED", TaskStatus.SUCCEEDED, Map.of("message", "巡检报告生成完成"));
            event(state, "DONE", TaskStatus.SUCCEEDED, Map.of());
        }, 1600, TimeUnit.MILLISECONDS);
    }

    private void scheduleExport(State state) {
        worker.schedule(() -> progress(state, 40, "导出巡检数据"), 300, TimeUnit.MILLISECONDS);
        worker.schedule(() -> {
            if (state.status == TaskStatus.CANCELLED) {
                return;
            }
            state.status = TaskStatus.SUCCEEDED;
            state.output = Map.of(
                    "fileName", "巡检报告-20261121.xlsx",
                    "rows", 12,
                    "downloadUrl", "/mock/export/inspection-20261121.xlsx",
                    "status", "DONE");
            state.progress = 100;
            state.currentStep = "DONE";
            event(state, "STATUS_CHANGED", TaskStatus.SUCCEEDED, Map.of("message", "导出完成"));
            event(state, "DONE", TaskStatus.SUCCEEDED, Map.of());
        }, 1000, TimeUnit.MILLISECONDS);
    }

    private void progress(State state, int progress, String step) {
        if (state.status == TaskStatus.CANCELLED) {
            return;
        }
        state.status = TaskStatus.RUNNING;
        state.progress = progress;
        state.currentStep = step;
        event(state, "PROGRESS", TaskStatus.RUNNING,
                Map.of("progress", progress, "step", step, "message", step));
    }

    private AdapterResult waiting(State state, Confirmation confirmation, String message) {
        state.confirmation = confirmation;
        state.status = TaskStatus.WAITING_CONFIRM;
        state.currentStep = "WAITING_CONFIRM";
        event(state, "STATUS_CHANGED", TaskStatus.WAITING_CONFIRM, Map.of("message", message));
        return AdapterResult.waiting(state.childTaskId, confirmation, List.copyOf(state.events));
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

    private static Map<String, Object> report(String reportNo, String reportType, String region,
                                              String createdAt, String status, int findingsCount) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("reportNo", reportNo);
        map.put("reportType", reportType);
        map.put("region", region);
        map.put("createdAt", createdAt);
        map.put("status", status);
        map.put("findingsCount", findingsCount);
        return map;
    }

    private static String string(Map<String, Object> input, String key, String fallback) {
        Object value = input == null ? null : input.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    /** LLM 可能传 daily/weekly/monthly，演示时统一成中文。 */
    private static String reportTypeLabel(String value) {
        if (value == null || value.isBlank()) {
            return "巡检报告";
        }
        return switch (value.toLowerCase()) {
            case "daily" -> "日报";
            case "weekly" -> "周报";
            case "monthly" -> "月报";
            default -> value;
        };
    }

    private static String periodLabel(String value) {
        if (value == null || value.isBlank()) {
            return "最近 7 天";
        }
        return switch (value.toLowerCase()) {
            case "today" -> "今天";
            case "week", "weekly" -> "最近 7 天";
            case "month", "monthly" -> "最近 30 天";
            default -> value;
        };
    }

    @Override
    public void close() {
        worker.shutdownNow();
        states.clear();
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
        volatile String actorId;
        volatile Map<String, Object> input = Map.of();

        State(String childTaskId) {
            this.childTaskId = childTaskId;
        }
    }
}
