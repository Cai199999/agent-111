package com.wuye.agent.demo;

import com.wuye.agent.agent.model.Capability;
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

import org.springframework.stereotype.Service;

/**
 * 内置的“企业 Agent 示例市场”：
 * 通过真实 HTTP Canonical v1 契约暴露两个企业 Agent，供运行时从 URL 安装。
 *
 * <p>它们和外部企业 Agent 一样走 HTTP，不是本地 Adapter 直连；
 * 主 Agent 会把会话摘要/全局画像通过 {@code _memory} 注入，证明记忆在主 Agent 层实现。</p>
 */
@Service
public class DemoEnterpriseAgentService {

    public record Definition(String id, String agentKey, String displayName,
                             List<Capability> capabilities) {
    }

    private final Map<String, Definition> definitions = new LinkedHashMap<>();
    private final Map<String, Map<String, State>> tasks = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    public DemoEnterpriseAgentService() {
        definitions.put("policy", new Definition(
                "policy", "policy-qa-agent", "政策法规问答 Agent",
                List.of(
                        capability("policy.qa.ask", "qa.ask", "政策法规问答", true, false),
                        capability("policy.document.review", "document.review", "政策文件审查", false, true)
                )));
        definitions.put("customer", new Definition(
                "customer", "customer-service-agent", "客服工单 Agent",
                List.of(
                        capability("customer.ticket.query", "ticket.query", "查询客服工单", true, false),
                        capability("customer.ticket.create", "ticket.create", "创建客服工单", false, true)
                )));
        definitions.keySet().forEach(id -> tasks.put(id, new ConcurrentHashMap<>()));
    }

    private static Capability capability(String id, String taskType, String name,
                                         boolean readOnly, boolean confirm) {
        return new Capability(id, "demo", taskType, id.split("\\.")[0], name, name,
                readOnly, confirm, List.of("SYNC"), true);
    }

    public Map<String, Object> card(String id) {
        Definition definition = definitions.get(id);
        if (definition == null) {
            return null;
        }
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("agentId", definition.agentKey());
        card.put("name", definition.displayName());
        card.put("version", "1.0.0");
        card.put("protocol", "agent-task-v1");
        card.put("a2aCompatibility", "canonical-http");
        card.put("capabilities", definition.capabilities().stream()
                .map(capability -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("capabilityId", capability.capabilityId());
                    item.put("taskType", capability.taskType());
                    item.put("name", capability.displayName());
                    item.put("description", capability.description());
                    item.put("readOnly", capability.readOnly());
                    item.put("requiresConfirmation", capability.requiresConfirmation());
                    item.put("executionModes", capability.executionModes());
                    return item;
                })
                .toList());
        card.put("endpoints", Map.of(
                "health", "/health",
                "ready", "/ready",
                "tasks", "/agent/v1/tasks"));
        card.put("features", Map.of(
                "cancel", true,
                "asyncDurability", "none",
                "streaming", false,
                "memoryInjection", true));
        return card;
    }

    public Map<String, Object> health(String id) {
        if (!definitions.containsKey(id)) {
            return null;
        }
        return Map.of("status", "UP", "ready", "UP");
    }

    public Map<String, Object> submit(String id, Map<String, Object> body) {
        Definition definition = definitions.get(id);
        if (definition == null) {
            return null;
        }
        Map<String, Object> input = map(body.get("input"));
        Map<String, Object> memory = map(input.get("_memory"));
        String taskType = string(body.get("taskType"));
        Capability capability = definition.capabilities().stream()
                .filter(item -> item.taskType().equals(taskType)
                        || item.capabilityId().equals(taskType))
                .findFirst().orElse(null);
        String taskId = "tsk_demo_" + seq.incrementAndGet();
        State state = new State(taskId, definition.agentKey(),
                capability == null ? taskType : capability.capabilityId(), input);
        tasks.get(id).put(taskId, state);

        if (capability == null) {
            state.status = "FAILED";
            state.error = Map.of(
                    "code", "CAPABILITY_NOT_FOUND",
                    "category", "NOT_FOUND",
                    "retryable", false,
                    "message", "未知 taskType：" + taskType);
            event(state, "ERROR", "FAILED", Map.of("message", "未知 taskType：" + taskType));
            return response(state);
        }

        switch (capability.capabilityId()) {
            case "policy.qa.ask" -> {
                String question = first(input, "question", "query", "keyword", "content");
                Map<String, Object> output = new LinkedHashMap<>();
                output.put("question", question);
                output.put("answer", "根据《土地管理法》及耕地保护相关规定，永久基本农田应重点用于粮食生产，"
                        + "不得擅自占用或改变用途；具体审批需结合地方实施细则。");
                output.put("citations", List.of(
                        Map.of("title", "土地管理法", "section", "耕地保护", "score", 0.93),
                        Map.of("title", "永久基本农田保护条例", "section", "用途管制", "score", 0.88)));
                memory(output, memory);
                state.status = "SUCCEEDED";
                state.output = output;
                event(state, "STATUS_CHANGED", "SUCCEEDED", Map.of("message", "政策问答完成"));
                event(state, "DONE", "SUCCEEDED", Map.of());
            }
            case "policy.document.review" -> {
                state.confirmation = confirmation("政策文件审查确认", "HIGH", List.of(
                        field("文件名称", first(input, "fileName", "name", "query")),
                        field("审查范围", first(input, "scope", "region", "内容"))));
                state.status = "WAITING_CONFIRM";
                event(state, "STATUS_CHANGED", "WAITING_CONFIRM", Map.of("message", "等待确认审查"));
            }
            case "customer.ticket.query" -> {
                Map<String, Object> output = new LinkedHashMap<>();
                output.put("tickets", List.of(
                        Map.of("ticketNo", "CS-2026-001", "customer", "江北客户",
                                "issue", "缴费记录查询", "status", "PENDING"),
                        Map.of("ticketNo", "CS-2026-002", "customer", "城南客户",
                                "issue", "服务预约修改", "status", "PROCESSING")));
                memory(output, memory);
                state.status = "SUCCEEDED";
                state.output = output;
                event(state, "STATUS_CHANGED", "SUCCEEDED", Map.of("message", "客服工单查询完成"));
                event(state, "DONE", "SUCCEEDED", Map.of());
            }
            case "customer.ticket.create" -> {
                state.confirmation = confirmation("客服工单创建确认", "HIGH", List.of(
                        field("客户", first(input, "customer", "customerName", "query")),
                        field("问题", first(input, "issue", "description", "内容")),
                        field("优先级", first(input, "priority", "NORMAL"))));
                state.status = "WAITING_CONFIRM";
                event(state, "STATUS_CHANGED", "WAITING_CONFIRM", Map.of("message", "等待确认创建工单"));
            }
            default -> {
                state.status = "FAILED";
                state.error = Map.of("code", "CAPABILITY_NOT_FOUND", "category", "NOT_FOUND",
                        "retryable", false, "message", "未实现的能力：" + capability.capabilityId());
                event(state, "ERROR", "FAILED", Map.of("message", "未实现的能力"));
            }
        }
        return response(state);
    }

    public Map<String, Object> get(String id, String taskId) {
        State state = state(id, taskId);
        return state == null ? null : response(state);
    }

    public Map<String, Object> confirm(String id, String taskId, Map<String, Object> body) {
        State state = state(id, taskId);
        if (state == null) {
            return null;
        }
        if (state.confirmation == null) {
            state.status = "FAILED";
            state.error = Map.of("code", "TASK_STATE_CONFLICT", "category", "CONFLICT",
                    "retryable", false, "message", "任务无需确认");
            return response(state);
        }
        String token = string(body.get("confirmationToken"));
        String expected = string(state.confirmation.get("confirmationToken"));
        if (token == null || !token.equals(expected)) {
            state.status = "FAILED";
            state.error = Map.of("code", "AUTH_FORBIDDEN", "category", "AUTH",
                    "retryable", false, "message", "确认令牌无效");
            return response(state);
        }
        String decision = string(body.get("decision"));
        state.confirmation.put("status", "APPROVE".equalsIgnoreCase(decision) ? "APPROVED" : "REJECTED");
        if (!"APPROVE".equalsIgnoreCase(decision)) {
            state.status = "REJECTED";
            event(state, "STATUS_CHANGED", "REJECTED", Map.of("message", "用户拒绝"));
            return response(state);
        }

        Map<String, Object> output = new LinkedHashMap<>();
        if ("policy.document.review".equals(state.capabilityId)) {
            output.put("reviewNo", "PR-2026-001");
            output.put("findingsCount", 3);
            output.put("status", "DONE");
        } else {
            output.put("ticketNo", "CS-2026-003");
            output.put("status", "CREATED");
        }
        memory(output, map(state.input.get("_memory")));
        state.status = "SUCCEEDED";
        state.output = output;
        event(state, "STATUS_CHANGED", "SUCCEEDED", Map.of("message", "确认后执行成功"));
        event(state, "DONE", "SUCCEEDED", Map.of());
        return response(state);
    }

    public Map<String, Object> cancel(String id, String taskId, Map<String, Object> body) {
        State state = state(id, taskId);
        if (state == null) {
            return null;
        }
        state.status = "CANCELLED";
        event(state, "STATUS_CHANGED", "CANCELLED",
                Map.of("reason", string(body.get("reason"))));
        return response(state);
    }

    public Map<String, Object> events(String id, String taskId, long afterSeq) {
        State state = state(id, taskId);
        if (state == null) {
            return null;
        }
        List<Map<String, Object>> items = state.events.stream()
                .filter(event -> ((Number) event.get("seq")).longValue() > afterSeq)
                .toList();
        return Map.of("items", items);
    }

    private State state(String id, String taskId) {
        Map<String, State> byTask = tasks.get(id);
        return byTask == null ? null : byTask.get(taskId);
    }

    private Map<String, Object> response(State state) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", state.taskId);
        result.put("agentId", state.agentKey);
        result.put("capabilityId", state.capabilityId);
        result.put("status", state.status);
        result.put("executionMode", "SYNC");
        result.put("cancelSupported", true);
        if (state.output != null) {
            result.put("output", state.output);
        }
        if (state.confirmation != null) {
            result.put("confirmation", state.confirmation);
        }
        if (state.error != null) {
            result.put("error", state.error);
        }
        result.put("events", state.events);
        return result;
    }

    private void memory(Map<String, Object> output, Map<String, Object> memory) {
        boolean used = memory != null && !memory.isEmpty();
        output.put("memoryUsed", used);
        if (used) {
            output.put("memoryHint", "已结合主 Agent 注入的会话摘要/用户画像");
        }
    }

    private Map<String, Object> confirmation(String title, String risk, List<Map<String, Object>> fields) {
        Map<String, Object> confirmation = new LinkedHashMap<>();
        confirmation.put("confirmationToken", UUID.randomUUID().toString());
        confirmation.put("title", title);
        confirmation.put("riskLevel", risk);
        confirmation.put("fields", fields);
        confirmation.put("expiresAt", Instant.now().plus(10, ChronoUnit.MINUTES).toString());
        confirmation.put("status", "PENDING");
        return confirmation;
    }

    private static Map<String, Object> field(String label, String value) {
        return Map.of("label", label, "value", value == null ? "待补充" : value);
    }

    private void event(State state, String type, String status, Map<String, Object> payload) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("seq", state.seq.incrementAndGet());
        event.put("type", type);
        event.put("status", status);
        event.put("payload", payload);
        event.put("createdAt", Instant.now().toString());
        state.events.add(event);
    }

    private static Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return Map.of();
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String first(Map<String, Object> input, String... keys) {
        for (String key : keys) {
            String value = string(input.get(key));
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static final class State {
        final String taskId;
        final String agentKey;
        final String capabilityId;
        final Map<String, Object> input;
        final AtomicLong seq = new AtomicLong();
        final List<Map<String, Object>> events = new CopyOnWriteArrayList<>();
        volatile String status = "PENDING";
        volatile Object output;
        volatile Map<String, Object> confirmation;
        volatile Map<String, Object> error;

        State(String taskId, String agentKey, String capabilityId, Map<String, Object> input) {
            this.taskId = taskId;
            this.agentKey = agentKey;
            this.capabilityId = capabilityId;
            this.input = input;
        }
    }
}
