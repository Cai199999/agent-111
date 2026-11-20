package com.wuye.agent.chat;

import com.wuye.agent.agent.adapter.AdapterRegistry;
import com.wuye.agent.agent.model.Capability;
import com.wuye.agent.agent.model.Confirmation;
import com.wuye.agent.agent.model.TaskEvent;
import com.wuye.agent.agent.model.TaskStatus;
import com.wuye.agent.auth.AuthUser;
import com.wuye.agent.auth.PolicyService;
import com.wuye.agent.common.ApiException;
import com.wuye.agent.common.Ids;
import com.wuye.agent.routing.LlmRouterClient;
import com.wuye.agent.routing.PendingIntent;
import com.wuye.agent.routing.RepairOutcome;
import com.wuye.agent.routing.RouteDecision;
import com.wuye.agent.routing.Router;
import com.wuye.agent.store.ChatMessage;
import com.wuye.agent.store.SessionState;
import com.wuye.agent.store.Store;
import com.wuye.agent.store.TaskRecord;
import com.wuye.agent.task.TaskService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 单对话框编排：路由 -> 任务 -> 确认 -> 结果。
 *
 * <p>SSE 只向浏览器暴露 taskNo 和摘要，确认令牌始终留在服务端。</p>
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final Store store;
    private final AdapterRegistry registry;
    private final Router router;
    private final LlmRouterClient llmRouterClient;
    private final TaskService taskService;
    private final PolicyService policyService;
    private final Ids ids;
    /** 多轮补参状态，按会话隔离；10 分钟过期。 */
    private final Map<String, PendingIntent> pendingIntents = new ConcurrentHashMap<>();

    public ChatService(Store store, AdapterRegistry registry,
                       Router router, LlmRouterClient llmRouterClient,
                       TaskService taskService, PolicyService policyService, Ids ids) {
        this.store = store;
        this.registry = registry;
        this.router = router;
        this.llmRouterClient = llmRouterClient;
        this.taskService = taskService;
        this.policyService = policyService;
        this.ids = ids;
    }

    public void send(String sessionNo, String content, String clientMessageId,
                     AuthUser user, String traceId, SseEmitter emitter) {
        try {
            SessionState session = store.getSession(sessionNo);
            if (session == null) {
                emit(emitter, "error", Map.of("code", "SESSION_NOT_FOUND", "message", "会话不存在", "retryable", false));
                emitter.complete();
                return;
            }
            if (content == null || content.isBlank()) {
                emit(emitter, "error", Map.of("code", "VALIDATION_ERROR", "message", "消息不能为空", "retryable", false));
                emitter.complete();
                return;
            }

            // 幂等：同一 clientMessageId 不重复执行（内存/MySQL 共用查询）
            if (clientMessageId != null && store.findMessageByClientId(session, clientMessageId) != null) {
                emit(emitter, "meta", Map.of("sessionNo", sessionNo, "messageNo", "duplicate", "traceId", traceId));
                emit(emitter, "delta", Map.of("content", "这条消息已经处理过了，请勿重复提交。"));
                emit(emitter, "done", Map.of("messageNo", "duplicate", "status", "DUPLICATE"));
                emitter.complete();
                return;
            }

            Map<String, Object> userMeta = new LinkedHashMap<>();
            if (clientMessageId != null) {
                userMeta.put("clientMessageId", clientMessageId);
            }
            ChatMessage userMessage = store.addMessage(session, "USER", content, "TEXT", userMeta, clientMessageId);
            emit(emitter, "meta", Map.of(
                    "sessionNo", sessionNo,
                    "messageNo", userMessage.messageNo(),
                    "traceId", traceId));

            PendingIntent pending = pendingIntents.get(sessionNo);
            if (pending != null && pending.expired()) {
                pendingIntents.remove(sessionNo);
                pending = null;
            }
            RouteDecision route = router.route(content, pending);
            Map<String, Object> routeEvent = new LinkedHashMap<>();
            routeEvent.put("capabilityId", route.capabilityId());
            routeEvent.put("confidence", route.confidence());
            routeEvent.put("router", route.router());
            routeEvent.put("reason", route.reason());
            emit(emitter, "route", routeEvent);

            if (route.needClarification()) {
                String pendingCapability = route.capabilityId() != null
                        ? route.capabilityId()
                        : (pending == null ? null : pending.capabilityId());
                if (pendingCapability != null) {
                    Map<String, Object> pendingArgs = new LinkedHashMap<>(
                            pending == null ? Map.of() : pending.args());
                    if (route.args() != null) {
                        pendingArgs.putAll(route.args());
                    }
                    pendingIntents.put(sessionNo, new PendingIntent(
                            pendingCapability, pendingArgs,
                            route.missingFields() == null ? List.of() : route.missingFields(),
                            Instant.now()));
                }
                ChatMessage assistant = store.addMessage(session, "ASSISTANT", route.clarification(),
                        "TEXT", Map.of("clarification", true), null);
                emit(emitter, "delta", Map.of("content", route.clarification()));
                emit(emitter, "done", Map.of("messageNo", assistant.messageNo(), "status", "CLARIFIED"));
                emitter.complete();
                return;
            }
            pendingIntents.remove(sessionNo);

            if (route.hasPlan() && route.plan().size() > 1) {
                emit(emitter, "plan", Map.of(
                        "steps", route.plan().stream()
                                .map(step -> Map.of(
                                        "capabilityId", step.capabilityId(),
                                        "args", step.args() == null ? Map.of() : step.args()))
                                .toList()));
            }

            ExecutionResult execution = executePlan(sessionNo, content, route, user, traceId);
            Capability capability = execution.capability();
            TaskRecord task = execution.task();
            emit(emitter, "task", taskEvent(task));
            for (TaskEvent event : task.events) {
                emit(emitter, "task_event", eventView(task.taskNo, event));
            }

            String answer;
            String contentType = "TEXT";
            String doneStatus = task.status.name();

            if (task.status == TaskStatus.WAITING_CONFIRM) {
                contentType = "CONFIRM";
                answer = "已生成待确认操作，请在任务卡片中确认。";
                emit(emitter, "confirm", Map.of(
                        "taskNo", task.taskNo,
                        "confirmation", confirmationView(task)));
            } else if (task.status == TaskStatus.PENDING || task.status == TaskStatus.RUNNING) {
                contentType = "TASK";
                answer = "任务已提交，正在执行，稍后可在任务卡片查看进度。";
            } else if (task.status == TaskStatus.SUCCEEDED) {
                contentType = "RESULT";
                answer = "子 Agent 返回结果如下。";
                // 原样透传子 Agent 的 output，不在主 Agent 侧改写/生成内容
                emit(emitter, "result", Map.of(
                        "taskNo", task.taskNo,
                        "capabilityId", capability.capabilityId(),
                        "output", task.output == null ? Map.of() : task.output));
            } else {
                answer = task.error == null ? "任务未成功完成。" : task.error.message();
            }

            Map<String, Object> assistantMeta = new LinkedHashMap<>();
            assistantMeta.put("capabilityId", capability.capabilityId());
            assistantMeta.put("agentKey", capability.agentKey());
            assistantMeta.put("taskNo", task.taskNo);
            assistantMeta.put("taskStatus", task.status.name());
            ChatMessage assistant = store.addMessage(session, "ASSISTANT", answer, contentType, assistantMeta, null);

            emit(emitter, "delta", Map.of("content", answer));
            emit(emitter, "done", Map.of(
                    "messageNo", assistant.messageNo(),
                    "taskNo", task.taskNo,
                    "status", doneStatus));
            emitter.complete();
        } catch (Exception ex) {
            log.warn("chat send failed traceId={} sessionNo={}", traceId, sessionNo, ex);
            try {
                emit(emitter, "error", Map.of(
                        "code", "INTERNAL_ERROR",
                        "message", "处理消息失败：" + ex.getMessage(),
                        "retryable", false));
                emitter.complete();
            } catch (Exception ignore) {
                emitter.completeWithError(ex);
            }
        }
    }

    public SessionState createSession(long userId, String title) {
        return store.createSession(userId, title);
    }

    public List<SessionState> listSessions(long userId) {
        return store.listSessions(userId);
    }

    public SessionState getSession(String sessionNo) {
        return store.getSession(sessionNo);
    }

    public List<ChatMessage> messages(String sessionNo) {
        return store.messages(sessionNo);
    }

    public boolean deleteSession(String sessionNo) {
        boolean deleted = store.deleteSession(sessionNo);
        if (deleted) {
            log.info("session deleted sessionNo={}", sessionNo);
        }
        return deleted;
    }

    private ExecutionResult executePlan(String sessionNo, String content, RouteDecision route,
                                        AuthUser user, String traceId) {
        List<RouteDecision.PlanStep> steps = route.plan();
        if (steps == null || steps.isEmpty()) {
            if (route.capabilityId() == null) {
                throw ApiException.badRequest("CAPABILITY_NOT_FOUND", "没有可执行的最终能力");
            }
            steps = List.of(new RouteDecision.PlanStep(route.capabilityId(), route.args()));
        }

        Map<String, Object> args = new LinkedHashMap<>(route.args() == null ? Map.of() : route.args());

        // 先执行只读前置步骤（如 profile.get），把结果合并进最终参数
        for (int i = 0; i < steps.size() - 1; i++) {
            RouteDecision.PlanStep step = steps.get(i);
            Capability pre = registry.capability(step.capabilityId())
                    .orElseThrow(() -> ApiException.notFound("CAPABILITY_NOT_FOUND",
                            "规划能力不存在：" + step.capabilityId()));
            policyService.requireCapability(user, pre.capabilityId());
            if (!pre.readOnly()) {
                throw ApiException.badRequest("PLAN_STEP_INVALID",
                        "规划的前置步骤必须是只读能力：" + pre.capabilityId());
            }
            TaskRecord preTask = taskService.submit(sessionNo, pre,
                    step.args() == null ? Map.of() : step.args(), user.actorId(), traceId);
            if (preTask.status != TaskStatus.SUCCEEDED) {
                String message = preTask.error == null ? "前置步骤失败" : preTask.error.message();
                throw new ApiException("PLAN_STEP_FAILED", 503,
                        "前置步骤 " + pre.capabilityId() + " 失败：" + message);
            }
            mergeOutput(args, preTask.output);
        }

        RouteDecision.PlanStep finalStep = steps.get(steps.size() - 1);
        Capability capability = registry.capability(finalStep.capabilityId())
                .orElseThrow(() -> ApiException.notFound("CAPABILITY_NOT_FOUND",
                        "能力不存在：" + finalStep.capabilityId()));
        policyService.requireCapability(user, capability.capabilityId());
        if (finalStep.args() != null) {
            args.putAll(finalStep.args());
        }

        TaskRecord task = taskService.submit(sessionNo, capability, args, user.actorId(), traceId);

        // 子 Agent 明确返回 VALIDATION 时，把错误回传 LLM 修正参数，最多重试一次
        if (task.status == TaskStatus.FAILED && task.error != null
                && "VALIDATION".equals(task.error.category())) {
            Optional<RepairOutcome> repaired = llmRouterClient.repair(
                    content, capability.capabilityId(), args,
                    task.error.code(), task.error.message());
            if (repaired.isPresent()
                    && !repaired.get().needClarification()
                    && repaired.get().args() != null
                    && !repaired.get().args().isEmpty()) {
                task = taskService.submit(sessionNo, capability, repaired.get().args(),
                        user.actorId(), traceId);
            }
        }
        return new ExecutionResult(capability, task);
    }

    private void mergeOutput(Map<String, Object> args, Object output) {
        if (!(output instanceof Map<?, ?> map)) {
            return;
        }
        for (String key : List.of("contactName", "contactPhone", "projectId", "buildingId",
                "roomId", "customerId", "ticketNo", "description", "locationText")) {
            Object value = map.get(key);
            if (value != null) {
                args.putIfAbsent(key, value);
            }
        }
    }

    private record ExecutionResult(Capability capability, TaskRecord task) {
    }

    private Map<String, Object> taskEvent(TaskRecord task) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("taskNo", task.taskNo);
        event.put("sessionNo", task.sessionNo);
        event.put("agentKey", task.agentKey);
        event.put("capabilityId", task.capabilityId);
        event.put("status", task.status.name());
        event.put("executionMode", task.executionMode);
        event.put("cancelSupported", task.cancelSupported);
        return event;
    }

    private Map<String, Object> eventView(String taskNo, TaskEvent event) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("taskNo", taskNo);
        view.put("seq", event.seq());
        view.put("type", event.type());
        view.put("status", event.status() == null ? null : event.status().name());
        view.put("payload", event.payload());
        view.put("createdAt", event.createdAt());
        return view;
    }

    private Map<String, Object> confirmationView(TaskRecord task) {
        Confirmation confirmation = task.confirmation;
        if (confirmation == null) {
            return Map.of();
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("title", confirmation.title());
        view.put("riskLevel", confirmation.riskLevel());
        view.put("fields", confirmationFields(confirmation, task.input));
        view.put("expiresAt", confirmation.expiresAt());
        view.put("status", confirmation.status());
        return view;
    }

    private static List<Map<String, Object>> confirmationFields(Confirmation confirmation,
                                                                Map<String, Object> input) {
        List<Map<String, Object>> fields = new java.util.ArrayList<>();
        if (confirmation.fields() != null) {
            for (Confirmation.Field field : confirmation.fields()) {
                fields.add(Map.of(
                        "label", field.label() == null ? "" : field.label(),
                        "value", field.value() == null ? "" : field.value()));
            }
        }
        if (fields.isEmpty() && input != null) {
            addField(fields, input, "description", "报修内容");
            addField(fields, input, "locationText", "位置");
            addField(fields, input, "ticketTypeCode", "工单类型");
            addField(fields, input, "priorityCode", "优先级");
            addField(fields, input, "contactName", "联系人");
        }
        return fields;
    }

    private static void addField(List<Map<String, Object>> fields, Map<String, Object> input,
                                 String key, String label) {
        Object value = input.get(key);
        if (value != null && !String.valueOf(value).isBlank()) {
            fields.add(Map.of("label", label, "value", String.valueOf(value)));
        }
    }

    private String formatOutput(Capability capability, Object output) {
        if (output == null) {
            return "任务已完成。";
        }
        if (output instanceof List<?> list) {
            if (list.isEmpty()) {
                return "没有查到结果。";
            }
            StringBuilder sb = new StringBuilder();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    if (map.containsKey("ticketNo")) {
                        sb.append("- ").append(map.get("ticketNo"))
                                .append("｜").append(text(map, "title"))
                                .append("｜状态 ").append(ticketStatus(map))
                                .append("｜优先级 ").append(priority(map))
                                .append("｜位置 ").append(first(map, "locationText", "location", "-"))
                                .append("｜处理人 ").append(first(map, "assigneeName", "assignee",
                                        "handlerName", "assigneeId", "未派单"))
                                .append("\n");
                    } else if (map.containsKey("ruleCode")) {
                        sb.append("- ").append(map.get("ruleCode"))
                                .append("｜").append(map.get("title"))
                                .append("｜").append(map.get("level"))
                                .append("\n");
                    } else if (map.containsKey("snippet")) {
                        sb.append("- ").append(map.get("title"))
                                .append("：").append(map.get("snippet"))
                                .append("\n");
                    } else {
                        sb.append("- ").append(map).append("\n");
                    }
                } else {
                    sb.append("- ").append(item).append("\n");
                }
            }
            return sb.toString().strip();
        }
        if (output instanceof Map<?, ?> map) {
            if (map.containsKey("summary")) {
                return map.get("summary") + "\n风险数：" + map.get("findingsCount")
                        + "\n结论：" + map.get("overall");
            }
            if (map.containsKey("ticketNo") && map.size() <= 3) {
                return "工单 " + map.get("ticketNo") + " 当前状态：" + map.get("status");
            }
            StringBuilder sb = new StringBuilder();
            map.forEach((k, v) -> sb.append(k).append("：").append(v).append("\n"));
            return sb.toString().strip();
        }
        return String.valueOf(output);
    }

    private String ticketStatus(Map<?, ?> map) {
        String raw = first(map, "statusName", "statusCode", "status", "PENDING");
        return switch (raw) {
            case "PENDING" -> "待处理(PENDING)";
            case "ASSIGNED" -> "已派单(ASSIGNED)";
            case "ACCEPTED" -> "已接单(ACCEPTED)";
            case "PROCESSING", "RUNNING" -> "处理中(PROCESSING)";
            case "RESOLVED" -> "已解决(RESOLVED)";
            case "VERIFIED" -> "已验证(VERIFIED)";
            case "CLOSED" -> "已关闭(CLOSED)";
            case "CANCELLED" -> "已取消(CANCELLED)";
            case "HOLD" -> "已挂起(HOLD)";
            default -> raw;
        };
    }

    private String priority(Map<?, ?> map) {
        String raw = first(map, "priorityName", "priorityCode", "priority", "-");
        return switch (raw) {
            case "LOW" -> "低(LOW)";
            case "NORMAL" -> "普通(NORMAL)";
            case "HIGH" -> "高(HIGH)";
            case "URGENT" -> "紧急(URGENT)";
            default -> raw;
        };
    }

    private static String text(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? "-" : String.valueOf(value);
    }

    private static String first(Map<?, ?> map, String... keysAndFallback) {
        for (int i = 0; i < keysAndFallback.length - 1; i++) {
            Object value = map.get(keysAndFallback[i]);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return keysAndFallback[keysAndFallback.length - 1];
    }

    private void emit(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
