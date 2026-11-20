package com.wuye.agent.chat;

import com.wuye.agent.agent.adapter.AdapterRegistry;
import com.wuye.agent.agent.model.Capability;
import com.wuye.agent.agent.model.Confirmation;
import com.wuye.agent.agent.model.TaskEvent;
import com.wuye.agent.agent.model.TaskStatus;
import com.wuye.agent.auth.AuthUser;
import com.wuye.agent.auth.PolicyService;
import com.wuye.agent.common.Ids;
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
    private final TaskService taskService;
    private final PolicyService policyService;
    private final Ids ids;

    public ChatService(Store store, AdapterRegistry registry,
                       Router router, TaskService taskService,
                       PolicyService policyService, Ids ids) {
        this.store = store;
        this.registry = registry;
        this.router = router;
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

            RouteDecision route = router.route(content);
            Map<String, Object> routeEvent = new LinkedHashMap<>();
            routeEvent.put("capabilityId", route.capabilityId());
            routeEvent.put("confidence", route.confidence());
            routeEvent.put("router", route.router());
            routeEvent.put("reason", route.reason());
            emit(emitter, "route", routeEvent);

            if (route.needClarification()) {
                ChatMessage assistant = store.addMessage(session, "ASSISTANT", route.clarification(),
                        "TEXT", Map.of("clarification", true), null);
                emit(emitter, "delta", Map.of("content", route.clarification()));
                emit(emitter, "done", Map.of("messageNo", assistant.messageNo(), "status", "CLARIFIED"));
                emitter.complete();
                return;
            }

            Capability capability = registry.capability(route.capabilityId()).orElse(null);
            if (capability == null) {
                String message = "当前没有可用的能力：" + route.capabilityId();
                ChatMessage assistant = store.addMessage(session, "ASSISTANT", message, "TEXT",
                        Map.of("error", "CAPABILITY_NOT_FOUND"), null);
                emit(emitter, "error", Map.of("code", "CAPABILITY_NOT_FOUND", "message", message, "retryable", false));
                emit(emitter, "done", Map.of("messageNo", assistant.messageNo(), "status", "FAILED"));
                emitter.complete();
                return;
            }

            if (!policyService.canUseCapability(user, capability.capabilityId())) {
                String message = "当前账号没有权限使用该能力。";
                ChatMessage assistant = store.addMessage(session, "ASSISTANT", message, "TEXT",
                        Map.of("error", "AUTH_FORBIDDEN"), null);
                emit(emitter, "error", Map.of(
                        "code", "AUTH_FORBIDDEN", "message", message, "retryable", false));
                emit(emitter, "done", Map.of("messageNo", assistant.messageNo(), "status", "FAILED"));
                emitter.complete();
                return;
            }

            TaskRecord task = taskService.submit(sessionNo, capability, route.args(), user.actorId(), traceId);
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
                        "confirmation", confirmationView(task.confirmation)));
            } else if (task.status == TaskStatus.PENDING || task.status == TaskStatus.RUNNING) {
                contentType = "TASK";
                answer = "任务已提交，正在执行，稍后可在任务卡片查看进度。";
            } else if (task.status == TaskStatus.SUCCEEDED) {
                answer = formatOutput(capability, task.output);
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

    private Map<String, Object> confirmationView(Confirmation confirmation) {
        if (confirmation == null) {
            return Map.of();
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("title", confirmation.title());
        view.put("riskLevel", confirmation.riskLevel());
        view.put("fields", confirmation.fields());
        view.put("expiresAt", confirmation.expiresAt());
        view.put("status", confirmation.status());
        return view;
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
                                .append("｜").append(map.get("title"))
                                .append("｜").append(map.get("status"))
                                .append("｜优先级 ").append(map.get("priority"))
                                .append("｜处理人 ").append(map.get("assignee"))
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

    private void emit(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
