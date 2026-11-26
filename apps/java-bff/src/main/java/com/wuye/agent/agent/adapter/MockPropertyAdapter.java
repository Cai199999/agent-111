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
            "0.11.0",
            "wrapped",
            "/agent/v1/health",
            "/agent/v1/ready",
            true,
            "db-recovery",
            List.of(
                    capability("property.ticket.query", "ticket.query", "查询工单", true, false),
                    capability("property.ticket.detail", "ticket.detail", "工单详情", true, false),
                    capability("property.ticket.similar", "ticket.similar", "相似工单", true, false),
                    capability("property.knowledge.search", "knowledge.search", "物业知识检索", true, false),
                    capability("property.masterdata.options", "masterdata.options", "主数据选项", true, false),
                    capability("property.profile.get", "profile.get", "读取个人资料", true, false),
                    capability("property.profile.update", "profile.update", "更新个人资料", false, true),
                    capability("property.ticket.create", "ticket.create", "创建工单", false, true),
                    capability("property.ticket.assign", "ticket.assign", "派单", false, true),
                    capability("property.ticket.accept", "ticket.accept", "接单", false, true),
                    capability("property.ticket.resolve", "ticket.resolve", "提交解决", false, true),
                    capability("property.ticket.verify", "ticket.verify", "验证", false, true),
                    capability("property.ticket.close", "ticket.close", "关闭", false, true),
                    capability("property.ticket.cancel", "ticket.cancel", "取消工单", false, true),
                    capability("property.ticket.hold", "ticket.hold", "挂起", false, true),
                    capability("property.ticket.resume", "ticket.resume", "恢复", false, true),
                    capability("property.ticket.finish", "ticket.finish", "结束", false, true),
                    capability("property.ticket.comment.add", "ticket.comment.add", "评论", false, true),
                    capability("property.ticket.material.add", "ticket.material.add", "材料", false, true),
                    capability("property.ticket.visit.add", "ticket.visit.add", "回访", false, true),
                    capability("property.ticket.export", "ticket.export", "导出 Excel", false, true),
                    // 0.11.0：低风险写，requiresConfirmation=false，直接执行。
                    capability("property.ticket.ai.analyze", "ticket.ai.analyze", "AI 分析工单", false, false)
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
    public boolean supportsManifest() {
        return true;
    }

    /**
     * 模拟物业 0.11.0 Manifest：版本 + 任务级 Skills。
     * 真实环境由 HTTP Adapter 从 /agent/v1/manifest 拉取，解析逻辑相同。
     */
    @Override
    public Map<String, Object> manifest() {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("agentId", AGENT_KEY);
        manifest.put("version", "0.11.0");
        manifest.put("capabilities", descriptor.capabilities().stream()
                .map(capability -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("capabilityId", capability.capabilityId());
                    item.put("taskType", capability.taskType());
                    item.put("displayName", capability.displayName());
                    item.put("description", capability.description());
                    item.put("readOnly", capability.readOnly());
                    item.put("requiresConfirmation", capability.requiresConfirmation());
                    item.put("executionModes", capability.executionModes());
                    item.put("enabled", capability.enabled());
                    return item;
                })
                .toList());
        manifest.put("skills", List.of(
                skill("property.ticket.query", "工单查询",
                        List.of("ticket.query", "ticket.detail", "ticket.similar"),
                        List.of("property.ticket.query", "property.ticket.detail",
                                "property.ticket.similar"), false, false),
                skill("property.ticket.create", "创建工单",
                        List.of("ticket.create"), List.of("property.ticket.create"),
                        false, true),
                skill("property.ticket.assign", "派单",
                        List.of("ticket.assign"), List.of("property.ticket.assign"),
                        false, true),
                skill("property.ticket.lifecycle", "工单流转",
                        List.of("ticket.accept", "ticket.resolve", "ticket.verify",
                                "ticket.close", "ticket.cancel", "ticket.hold",
                                "ticket.resume", "ticket.finish"),
                        List.of("property.ticket.accept", "property.ticket.resolve",
                                "property.ticket.verify", "property.ticket.close",
                                "property.ticket.cancel", "property.ticket.hold",
                                "property.ticket.resume", "property.ticket.finish"),
                        false, true),
                skill("property.ticket.comment", "工单沟通",
                        List.of("ticket.comment.add", "ticket.material.add", "ticket.visit.add"),
                        List.of("property.ticket.comment.add", "property.ticket.material.add",
                                "property.ticket.visit.add"), false, true),
                skill("property.ticket.export", "工单导出",
                        List.of("ticket.export"), List.of("property.ticket.export"),
                        false, true),
                skill("property.ticket.analyze", "AI 工单分析",
                        List.of("ticket.ai.analyze"), List.of("property.ticket.ai.analyze"),
                        false, false),
                skill("property.profile", "个人资料",
                        List.of("profile.get", "profile.update"),
                        List.of("property.profile.get", "property.profile.update"),
                        false, true),
                skill("property.knowledge", "物业知识检索",
                        List.of("knowledge.search"), List.of("property.knowledge.search"),
                        false, false),
                skill("property.masterdata", "主数据选项",
                        List.of("masterdata.options"), List.of("property.masterdata.options"),
                        false, false)
        ));
        return manifest;
    }

    private static Map<String, Object> skill(String skillId, String displayName,
                                             List<String> taskTypes, List<String> capabilityIds,
                                             boolean partial, boolean requiresConfirmation) {
        Map<String, Object> skill = new LinkedHashMap<>();
        skill.put("skillId", skillId);
        skill.put("displayName", displayName);
        skill.put("description", displayName);
        skill.put("taskTypes", taskTypes);
        skill.put("capabilities", capabilityIds);
        skill.put("partial", partial);
        skill.put("requiresConfirmation", requiresConfirmation);
        return skill;
    }

    @Override
    public AdapterResult submit(AdapterTaskRequest request) {
        String childTaskId = "tsk_p_" + childSeq.incrementAndGet();
        State state = new State(childTaskId);
        state.capabilityId = request.capabilityId();
        state.input = request.input() == null ? Map.of() : request.input();
        state.actorId = request.actorId();
        states.put(childTaskId, state);

        switch (request.capabilityId()) {
            case "property.ticket.ai.analyze" -> {
                // 0.11.0 低风险写：requiresConfirmation=false，直接返回分析结果。
                String ticketNo = string(request.input(), "ticketNo", "GD20261119000107");
                Map<String, Object> analysis = new LinkedHashMap<>();
                analysis.put("ticketNo", ticketNo);
                analysis.put("summary", "现场为水管漏水，建议优先派给给排水班组。");
                analysis.put("causes", List.of("管件老化", "接口松动"));
                analysis.put("actions", List.of("关闭入户总阀", "更换管件", "回访确认"));
                analysis.put("risk", "NORMAL");
                return succeed(state, analysis);
            }
            case "property.ticket.query" -> {
                String keyword = string(request.input(), "keyword", "");
                String status = string(request.input(), "status", "");
                String ticketNo = string(request.input(), "ticketNo", "");
                List<Map<String, Object>> all = List.of(
                        ticket("GD20261119000107", "3栋2单元水管漏水", "PENDING", "HIGH", "未派单"),
                        ticket("GD20261119000108", "1栋大堂照明故障", "ASSIGNED", "NORMAL", "10002"),
                        ticket("GD20261119000109", "地下车库道闸异常", "PENDING", "LOW", "未派单"));
                List<Map<String, Object>> filtered = all.stream()
                        .filter(item -> ticketNo.isBlank()
                                || ticketNo.equals(String.valueOf(item.get("ticketNo"))))
                        .filter(item -> status.isBlank()
                                || status.equalsIgnoreCase(String.valueOf(item.get("status"))))
                        .filter(item -> keyword.isBlank() || ticketMatches(keyword, item))
                        .toList();
                return succeed(state, unwrapProperty(wrapProperty(filtered)));
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
            case "property.masterdata.options" -> {
                Object data = Map.of(
                        "projects", List.of(Map.of("id", 100, "name", "示范项目")),
                        "buildings", List.of(Map.of("id", 101, "name", "1栋", "projectId", 100)),
                        "rooms", List.of(Map.of("id", 1001, "name", "101", "buildingId", 101)),
                        "customers", List.of(Map.of("id", 2001, "name", "示例客户", "projectId", 100)),
                        "equipment", List.of(),
                        "serviceItems", List.of(Map.of("id", 3001, "name", "给排水")));
                return succeed(state, data);
            }
            case "property.profile.get" -> {
                Object data = Map.of(
                        "contactName", "资料联系人",
                        "phoneMask", "138****5678",
                        "projectId", 100,
                        "buildingId", 101,
                        "roomId", 1001,
                        "customerId", 2001,
                        "reviewStatus", "10001".equals(request.actorId()) ? "APPROVED" : "PENDING");
                return succeed(state, data);
            }
            case "property.profile.update" -> {
                Confirmation confirmation = confirmation(
                        "个人资料确认",
                        "MEDIUM",
                        List.of(
                                new Confirmation.Field("联系人", string(request.input(), "contactName", "未填写")),
                                new Confirmation.Field("联系电话", string(request.input(), "phone", "未填写")),
                                new Confirmation.Field("项目", string(request.input(), "projectId", "未选择"))
                        ));
                state.confirmation = confirmation;
                state.status = TaskStatus.WAITING_CONFIRM;
                state.currentStep = "WAITING_CONFIRM";
                event(state, "STATUS_CHANGED", TaskStatus.WAITING_CONFIRM, Map.of("message", "等待用户确认个人资料"));
                return AdapterResult.waiting(childTaskId, confirmation, List.copyOf(state.events));
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
            // 其余工单写操作走统一确认 + 模拟成功，避免演示时因未实现的边缘能力中断。
            // 真实接入时由物业子 Agent 返回各自的确认字段和状态机。
            case "property.ticket.accept", "property.ticket.resolve", "property.ticket.verify",
                 "property.ticket.close", "property.ticket.cancel", "property.ticket.hold",
                 "property.ticket.resume", "property.ticket.finish", "property.ticket.comment.add",
                 "property.ticket.material.add", "property.ticket.visit.add",
                 "property.ticket.export" -> {
                String ticketNo = string(request.input(), "ticketNo", "GD20261119000107");
                String action = actionName(request.capabilityId());
                Confirmation confirmation = confirmation(
                        action + "确认",
                        "HIGH",
                        List.of(
                                new Confirmation.Field("工单", ticketNo),
                                new Confirmation.Field("操作", action)
                        ));
                state.confirmation = confirmation;
                state.status = TaskStatus.WAITING_CONFIRM;
                state.currentStep = "WAITING_CONFIRM";
                event(state, "STATUS_CHANGED", TaskStatus.WAITING_CONFIRM,
                        Map.of("message", "等待用户确认" + action));
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
            state.output = switch (state.capabilityId) {
                case "property.ticket.create" -> Map.of("ticketNo", "GD202611200001", "status", "PENDING");
                case "property.profile.update" -> profileOutput(state);
                case "property.ticket.comment.add" -> Map.of(
                        "ticketNo", string(state.input, "ticketNo", "GD20261119000107"),
                        "commentId", "CM202611210001", "status", "CREATED");
                case "property.ticket.material.add" -> Map.of(
                        "ticketNo", string(state.input, "ticketNo", "GD20261119000107"),
                        "materialId", "MT202611210001", "status", "CREATED");
                case "property.ticket.visit.add" -> Map.of(
                        "ticketNo", string(state.input, "ticketNo", "GD20261119000107"),
                        "visitId", "VS202611210001", "status", "CREATED");
                case "property.ticket.export" -> Map.of(
                        "fileName", "工单导出.xlsx", "downloadUrl", "/mock/export/tickets.xlsx");
                case "property.ticket.assign" -> Map.of(
                        "ticketNo", string(state.input, "ticketNo", "GD20261119000107"),
                        "assigneeId", string(state.input, "assigneeId", "10002"), "status", "ASSIGNED");
                default -> Map.of(
                        "ticketNo", string(state.input, "ticketNo", "GD20261119000107"),
                        "status", successStatus(state.capabilityId));
            };
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

    /** 自然语言关键词匹配：标题/工单号，支持“漏水”“1栋”“照明”等 token。 */
    private static boolean ticketMatches(String keyword, Map<String, Object> item) {
        String text = keyword == null ? "" : keyword.replaceAll("[\\s，。！？!?：:、]", "");
        String title = String.valueOf(item.get("title"));
        String no = String.valueOf(item.get("ticketNo"));
        if (text.contains(title) || text.contains(no) || title.contains(text)) {
            return true;
        }
        for (String token : keyword.split("[\\s，。！？!?：:、]+")) {
            if (token.length() >= 2 && (title.contains(token) || no.contains(token))) {
                return true;
            }
        }
        // “把3栋2单元水管漏水那个工单派给张师傅”这类长句：用 >=3 字的公共片段匹配。
        return sharesFragment(text, title, 3) || sharesFragment(text, no, 4);
    }

    private static boolean sharesFragment(String a, String b, int min) {
        if (a == null || b == null || a.length() < min || b.length() < min) {
            return false;
        }
        for (int length = Math.min(a.length(), b.length()); length >= min; length--) {
            for (int i = 0; i + length <= a.length(); i++) {
                if (b.contains(a.substring(i, i + length))) {
                    return true;
                }
            }
        }
        return false;
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

    private static Map<String, Object> profileOutput(State state) {
        String phone = string(state.input, "phone", "");
        String masked = phone.length() >= 7
                ? phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4)
                : phone;
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("contactName", string(state.input, "contactName", "资料联系人"));
        output.put("phoneMask", masked);
        output.put("projectId", state.input.getOrDefault("projectId", 100));
        output.put("buildingId", state.input.getOrDefault("buildingId", 101));
        output.put("roomId", state.input.getOrDefault("roomId", 1001));
        output.put("customerId", state.input.getOrDefault("customerId", 2001));
        output.put("reviewStatus", "10001".equals(state.actorId) ? "APPROVED" : "PENDING");
        return output;
    }

    /** 统一写操作的确认文案。 */
    private static String actionName(String capabilityId) {
        return switch (capabilityId) {
            case "property.ticket.accept" -> "接单";
            case "property.ticket.resolve" -> "提交解决";
            case "property.ticket.verify" -> "验证";
            case "property.ticket.close" -> "关闭工单";
            case "property.ticket.cancel" -> "取消工单";
            case "property.ticket.hold" -> "挂起";
            case "property.ticket.resume" -> "恢复";
            case "property.ticket.finish" -> "结束";
            case "property.ticket.comment.add" -> "添加评论";
            case "property.ticket.material.add" -> "添加材料";
            case "property.ticket.visit.add" -> "添加回访";
            case "property.ticket.export" -> "导出工单";
            default -> "工单操作";
        };
    }

    /** 写操作确认后的模拟终态。 */
    private static String successStatus(String capabilityId) {
        return switch (capabilityId) {
            case "property.ticket.accept" -> "ACCEPTED";
            case "property.ticket.resolve" -> "RESOLVED";
            case "property.ticket.verify" -> "VERIFIED";
            case "property.ticket.close" -> "CLOSED";
            case "property.ticket.cancel" -> "CANCELLED";
            case "property.ticket.hold" -> "HOLD";
            case "property.ticket.resume" -> "PROCESSING";
            case "property.ticket.finish" -> "FINISHED";
            default -> "SUCCEEDED";
        };
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
