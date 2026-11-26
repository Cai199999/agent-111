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
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

/**
 * 第三个演示子 Agent：时空资产 Agent（Mock）。
 *
 * <p>它的存在是为了证明“可扩展的多 Agent 平台”：新增一个 Agent 只需要
 * 实现 AgentAdapter + 注册为 Spring Bean + 补权限数据，主 Agent 的
 * 路由、确认、审计、人工队列、看板、Agent Card 全部自动复用。</p>
 *
 * <p>不接真实 GIS 引擎，只返回可演示的地块/变化图斑/巡查任务数据。</p>
 */
@Component
public class MockGisAdapter implements AgentAdapter {

    private static final String AGENT_KEY = "geo-asset-agent";
    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final AtomicLong childSeq = new AtomicLong();

    private final AgentDescriptor descriptor = new AgentDescriptor(
            AGENT_KEY,
            "时空资产 Agent（Mock）",
            "gis-v01",
            "0.1.0",
            "raw",
            "/health",
            "/ready",
            true,
            "none",
            List.of(
                    capability("gis.parcel.query", "parcel.query", "查询地块", true, false),
                    capability("gis.change.query", "change.query", "查询遥感变化图斑", true, false),
                    capability("gis.patrol.create", "patrol.create", "创建巡查任务", false, true)
            ));

    private static Capability capability(String id, String taskType, String name,
                                         boolean readOnly, boolean confirm) {
        return new Capability(id, AGENT_KEY, taskType, "gis", name, name,
                readOnly, confirm, List.of("SYNC"), true);
    }

    @Override
    public AgentDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public AdapterResult submit(AdapterTaskRequest request) {
        String childTaskId = "tsk_g_" + childSeq.incrementAndGet();
        State state = new State(childTaskId);
        state.capabilityId = request.capabilityId();
        state.input = request.input() == null ? Map.of() : request.input();
        state.actorId = request.actorId();
        states.put(childTaskId, state);

        switch (request.capabilityId()) {
            case "gis.parcel.query" -> {
                String keyword = string(request.input(), "keyword", "");
                String parcelNo = string(request.input(), "parcelNo", "");
                List<Map<String, Object>> all = List.of(
                        parcel("P-2026-001", "江北新区 A-01 地块", "江北新区",
                                128.6, "商业用地", "已登记"),
                        parcel("P-2026-002", "江北新区 B-07 地块", "江北新区",
                                86.4, "住宅用地", "已登记"),
                        parcel("P-2026-003", "城南片区 C-12 地块", "城南片区",
                                45.2, "公共服务用地", "待核查"));
                if (!parcelNo.isBlank()) {
                    all = all.stream()
                            .filter(item -> parcelNo.equals(String.valueOf(item.get("parcelNo"))))
                            .toList();
                }
                if (!keyword.isBlank()) {
                    List<Map<String, Object>> strong = all.stream()
                            .filter(item -> strongMatch(keyword, item))
                            .toList();
                    List<Map<String, Object>> weak = all.stream()
                            .filter(item -> weakMatch(keyword, item))
                            .toList();
                    all = !strong.isEmpty() ? strong : weak;
                }
                return succeed(state, all);
            }
            case "gis.change.query" -> {
                String keyword = string(request.input(), "keyword", "");
                String parcelNo = string(request.input(), "parcelNo", "");
                String level = string(request.input(), "level", "");
                if (level.isBlank() && (keyword.contains("紧急") || keyword.contains("高"))) {
                    level = "HIGH";
                } else if ("高".equals(level) || "紧急".equals(level)) {
                    level = "HIGH";
                } else if ("一般".equals(level) || "普通".equals(level)) {
                    level = "GENERAL";
                }
                List<Map<String, Object>> all = List.of(
                        change("CH-2026-001", "P-2026-001", "新增建设", 3.2, "HIGH", "待复核"),
                        change("CH-2026-002", "P-2026-002", "地表覆盖变化", 1.8, "GENERAL", "待复核"),
                        change("CH-2026-003", "P-2026-003", "疑似违建", 0.9, "HIGH", "已派发"));
                String levelFilter = level;
                all = all.stream()
                        .filter(item -> parcelNo.isBlank()
                                || String.valueOf(item.get("parcelNo")).contains(parcelNo))
                        .filter(item -> levelFilter.isBlank()
                                || levelFilter.equals(String.valueOf(item.get("level"))))
                        .filter(item -> keyword.isBlank() || changeMatches(keyword, item))
                        .toList();
                return succeed(state, all);
            }
            case "gis.patrol.create" -> {
                String parcelNo = string(request.input(), "parcelNo", "P-2026-001");
                String patrolType = string(request.input(), "patrolType", "变化图斑复核");
                Confirmation confirmation = confirmation(
                        "巡查任务确认",
                        "HIGH",
                        List.of(
                                new Confirmation.Field("地块", parcelNo),
                                new Confirmation.Field("巡查类型", patrolType),
                                new Confirmation.Field("截止时间",
                                        string(request.input(), "deadline", "2026-11-25"))
                        ));
                state.confirmation = confirmation;
                state.status = TaskStatus.WAITING_CONFIRM;
                state.currentStep = "WAITING_CONFIRM";
                event(state, "STATUS_CHANGED", TaskStatus.WAITING_CONFIRM,
                        Map.of("message", "等待用户确认巡查任务"));
                return AdapterResult.waiting(childTaskId, confirmation, List.copyOf(state.events));
            }
            default -> {
                AgentError error = new AgentError("CAPABILITY_NOT_FOUND", "NOT_FOUND", false,
                        "未实现的时空资产能力：" + request.capabilityId());
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

        if (decision == Decision.APPROVE) {
            state.status = TaskStatus.SUCCEEDED;
            state.output = Map.of(
                    "patrolNo", "PT-2026-001",
                    "parcelNo", string(state.input, "parcelNo", "P-2026-001"),
                    "patrolType", string(state.input, "patrolType", "变化图斑复核"),
                    "status", "CREATED");
            state.progress = 100;
            state.currentStep = "DONE";
            state.confirmation = state.confirmation.withStatus("APPROVED");
            event(state, "STATUS_CHANGED", TaskStatus.SUCCEEDED, Map.of("message", "巡查任务已创建"));
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

    private static Map<String, Object> parcel(String parcelNo, String name, String region,
                                              double areaMu, String landUse, String status) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("parcelNo", parcelNo);
        map.put("parcelName", name);
        map.put("region", region);
        map.put("areaMu", areaMu);
        map.put("landUse", landUse);
        map.put("status", status);
        return map;
    }

    /** 编号/名称/精确 token 命中，优先于区域/用途等弱条件。 */
    private static boolean strongMatch(String keyword, Map<String, Object> item) {
        String text = compact(keyword);
        String no = String.valueOf(item.get("parcelNo"));
        String name = String.valueOf(item.get("parcelName"));
        String region = String.valueOf(item.get("region"));
        if (text.contains(no) || text.contains(name)) {
            return true;
        }
        // 只输入区域名（如“江北新区”）不算强命中，否则会同时命中多个地块。
        if (text.length() >= 3 && name.contains(text) && !region.contains(text)) {
            return true;
        }
        for (String token : tokens(keyword)) {
            if (token.length() >= 2 && !region.contains(token)
                    && (name.contains(token) || no.contains(token))) {
                return true;
            }
        }
        return false;
    }

    /** 区域/用途等弱条件；只在没有强命中时使用。 */
    private static boolean weakMatch(String keyword, Map<String, Object> item) {
        String text = compact(keyword);
        String region = String.valueOf(item.get("region"));
        String landUse = String.valueOf(item.get("landUse"));
        if (text.contains(region) || text.contains(landUse)) {
            return true;
        }
        for (String token : tokens(keyword)) {
            if (token.length() >= 2 && (region.contains(token) || landUse.contains(token))) {
                return true;
            }
        }
        return false;
    }

    private static boolean changeMatches(String keyword, Map<String, Object> item) {
        String text = compact(keyword);
        String changeNo = String.valueOf(item.get("changeNo"));
        String parcelNo = String.valueOf(item.get("parcelNo"));
        String changeType = String.valueOf(item.get("changeType"));
        String status = String.valueOf(item.get("status"));
        if (text.contains(changeNo) || text.contains(parcelNo)
                || text.contains(changeType) || text.contains(status)) {
            return true;
        }
        // “最近/紧急/高/图斑”等筛选意图由 level/其它条件处理，不要误杀全部结果。
        return text.contains("图斑") || text.contains("遥感") || text.contains("变化")
                || text.contains("紧急") || text.contains("高");
    }

    private static String compact(String text) {
        return text == null ? "" : text.replaceAll("[\\s，。！？!?：:、]", "");
    }

    private static String[] tokens(String text) {
        return text == null ? new String[0]
                : text.split("[\\s，。！？!?：:、]+");
    }

    private static Map<String, Object> change(String changeNo, String parcelNo, String changeType,
                                              double areaMu, String level, String status) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("changeNo", changeNo);
        map.put("parcelNo", parcelNo);
        map.put("changeType", changeType);
        map.put("areaMu", areaMu);
        map.put("level", level);
        map.put("status", status);
        return map;
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
        volatile String actorId;
        volatile Map<String, Object> input = Map.of();

        State(String childTaskId) {
            this.childTaskId = childTaskId;
        }
    }
}
