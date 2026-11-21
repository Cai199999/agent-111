package com.wuye.agent.chat;

import com.wuye.agent.agent.adapter.AdapterRegistry;
import com.wuye.agent.agent.catalog.AgentCatalogService;
import com.wuye.agent.agent.catalog.OptionCatalogService;
import com.wuye.agent.agent.install.DynamicAgentRegistry;
import com.wuye.agent.agent.model.Capability;
import com.wuye.agent.agent.model.Confirmation;
import com.wuye.agent.agent.model.TaskEvent;
import com.wuye.agent.agent.model.TaskStatus;
import com.wuye.agent.auth.AuthUser;
import com.wuye.agent.auth.PolicyService;
import com.wuye.agent.common.ApiException;
import com.wuye.agent.common.Ids;
import com.wuye.agent.memory.MainAgentTools;
import com.wuye.agent.memory.MemoryService;
import com.wuye.agent.memory.UserProfileService;
import com.wuye.agent.routing.LlmRouterClient;
import com.wuye.agent.routing.PendingIntent;
import com.wuye.agent.routing.RouteDecision;
import com.wuye.agent.routing.Router;
import com.wuye.agent.store.ChatMessage;
import com.wuye.agent.store.SessionState;
import com.wuye.agent.store.Store;
import com.wuye.agent.store.TaskRecord;
import com.wuye.agent.task.RecoveryOutcome;
import com.wuye.agent.task.TaskRecoveryService;
import com.wuye.agent.task.TaskService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
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
    private final AgentCatalogService catalogService;
    private final OptionCatalogService optionCatalogService;
    private final DynamicAgentRegistry dynamicAgentRegistry;
    private final Router router;
    private final LlmRouterClient llmRouterClient;
    private final TaskService taskService;
    private final TaskRecoveryService recoveryService;
    private final PolicyService policyService;
    private final MemoryService memoryService;
    private final UserProfileService userProfileService;
    private final Ids ids;
    /** 多轮补参状态，按会话隔离；10 分钟过期。 */
    private final Map<String, PendingIntent> pendingIntents = new ConcurrentHashMap<>();
    /** 平台目录追问上下文：记住上一轮聚焦的 Agent。 */
    private final Map<String, String> lastCatalogAgent = new ConcurrentHashMap<>();

    public ChatService(Store store, AdapterRegistry registry, AgentCatalogService catalogService,
                       OptionCatalogService optionCatalogService,
                       DynamicAgentRegistry dynamicAgentRegistry,
                       Router router, LlmRouterClient llmRouterClient,
                       TaskService taskService, TaskRecoveryService recoveryService,
                       PolicyService policyService, MemoryService memoryService,
                       UserProfileService userProfileService, Ids ids) {
        this.store = store;
        this.registry = registry;
        this.catalogService = catalogService;
        this.optionCatalogService = optionCatalogService;
        this.dynamicAgentRegistry = dynamicAgentRegistry;
        this.router = router;
        this.llmRouterClient = llmRouterClient;
        this.taskService = taskService;
        this.recoveryService = recoveryService;
        this.policyService = policyService;
        this.memoryService = memoryService;
        this.userProfileService = userProfileService;
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
            RouteDecision route = router.route(content, pending, session.summary,
                    userProfileService.contextFor(user.id()));
            // 用户用名称/描述而不是内部编码时：先做一次只读语义解析，唯一命中就继续执行。
            if (route.needClarification()) {
                RouteDecision resolved = semanticResolve(route, content, user, traceId);
                if (resolved != null) {
                    route = resolved;
                }
            }
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
                memoryService.updateAsync(sessionNo);
                return;
            }
            pendingIntents.remove(sessionNo);

            // 规则路由可能命中“尚未安装”的动态 Agent 能力：友好提示安装，不抛技术错误。
            if (route.capabilityId() != null
                    && !MainAgentTools.isMainTool(route.capabilityId())
                    && registry.capability(route.capabilityId()).isEmpty()) {
                String message = "这个能力还没有安装或暂不可用。可以到侧边栏「Agent 状态」安装对应 Agent 后再试。";
                ChatMessage assistant = store.addMessage(session, "ASSISTANT", message,
                        "TEXT", Map.of("noCapability", true), null);
                emit(emitter, "feedback", Map.of(
                        "action", "NO_CAPABILITY",
                        "message", message,
                        "retryable", false));
                emit(emitter, "delta", Map.of("content", message));
                emit(emitter, "done", Map.of(
                        "messageNo", assistant.messageNo(),
                        "status", "CLARIFIED"));
                emitter.complete();
                memoryService.updateAsync(sessionNo);
                return;
            }

            if (route.capabilityId() != null && MainAgentTools.isMainTool(route.capabilityId())) {
                handleMainTool(route, user, session, content, emitter);
                return;
            }

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
            RecoveryOutcome recovery = execution.recovery();
            emit(emitter, "task", taskEvent(task));
            for (TaskEvent event : task.events) {
                emit(emitter, "task_event", eventView(task.taskNo, event));
            }

            // 自修复要求追问：保留 pending 上下文，下一轮用户补充后继续，不把技术错误抛给用户。
            if (recovery != null && recovery.clarification()) {
                String pendingCapability = recovery.pendingCapabilityId() != null
                        ? recovery.pendingCapabilityId() : capability.capabilityId();
                pendingIntents.put(sessionNo, new PendingIntent(
                        pendingCapability,
                        recovery.pendingArgs(),
                        recovery.missingFields(),
                        Instant.now()));
                ChatMessage assistant = store.addMessage(session, "ASSISTANT", recovery.message(),
                        "TEXT", Map.of("clarification", true, "recovery", recovery.action()), null);
                emit(emitter, "feedback", Map.of(
                        "taskNo", task.taskNo,
                        "action", recovery.action(),
                        "message", recovery.message(),
                        "missingFields", recovery.missingFields(),
                        "retryable", false));
                emit(emitter, "delta", Map.of("content", recovery.message()));
                emit(emitter, "done", Map.of(
                        "messageNo", assistant.messageNo(),
                        "taskNo", task.taskNo,
                        "status", "CLARIFIED"));
                emitter.complete();
                memoryService.updateAsync(sessionNo);
                return;
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
                if (recovery != null && ("RETRIED".equals(recovery.action())
                        || "ALTERNATIVE".equals(recovery.action()))) {
                    emit(emitter, "feedback", Map.of(
                            "taskNo", task.taskNo,
                            "action", "RECOVERED",
                            "message", "第一次调用没有成功，已自动修复并重试成功。",
                            "retryable", false));
                }
                // 自然语言只做“解释”，原始 output 仍然原样透传
                answer = llmRouterClient.explain(content, capability.capabilityId(), task.output)
                        .orElse("子 Agent 返回结果如下。");
                emit(emitter, "result", Map.of(
                        "taskNo", task.taskNo,
                        "capabilityId", capability.capabilityId(),
                        "output", task.output == null ? Map.of() : task.output));
            } else {
                // 失败/转人工：只给用户可行动的反馈，原始错误保留在任务事件/审计里。
                answer = recovery != null && recovery.message() != null
                        ? recovery.message()
                        : "这次没有处理成功，我已经记录了问题，并转人工跟进。";
                emit(emitter, "feedback", Map.of(
                        "taskNo", task.taskNo,
                        "action", recovery == null ? "TRANSFERRED" : recovery.action(),
                        "message", answer,
                        "retryable", false));
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
            memoryService.updateAsync(sessionNo);
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

    /**
     * 名称/描述 -> 内部 ID 的只读解析。
     *
     * <p>用户不会输入 ticketNo/parcelNo/contractId。缺 ID 时先查：
     * 唯一命中则合并继续；多个候选则让用户选；查不到才保留原追问。</p>
     */
    private RouteDecision semanticResolve(RouteDecision route, String content,
                                          AuthUser user, String traceId) {
        String capabilityId = route.capabilityId();
        if (capabilityId == null) {
            return route;
        }
        Map<String, Object> args = new LinkedHashMap<>(
                route.args() == null ? Map.of() : route.args());
        boolean changed = false;
        List<?> candidates = null;
        String candidateLabel = null;

        if ("gis.patrol.create".equals(capabilityId) && blank(args.get("parcelNo"))) {
            Lookup lookup = lookup("gis.parcel.query", Map.of("keyword", content), user, traceId);
            if (lookup.single() != null) {
                args.put("parcelNo", lookup.single().get("parcelNo"));
                changed = true;
            } else if (lookup.items().size() > 1) {
                candidates = lookup.items();
                candidateLabel = "地块";
            }
        }
        if (capabilityId.startsWith("property.ticket.")
                && !"property.ticket.query".equals(capabilityId)
                && !"property.ticket.create".equals(capabilityId)
                && blank(args.get("ticketNo"))) {
            Lookup lookup = lookup("property.ticket.query", Map.of("keyword", content), user, traceId);
            if (lookup.single() != null) {
                args.put("ticketNo", lookup.single().get("ticketNo"));
                changed = true;
            } else if (lookup.items().size() > 1) {
                candidates = lookup.items();
                candidateLabel = "工单";
            }
        }
        if ("contract.review".equals(capabilityId)
                && blank(args.get("contractId")) && blank(args.get("contractNo"))) {
            Lookup lookup = lookup("contract.resolve", Map.of("query", content), user, traceId);
            if (lookup.single() != null && lookup.single().get("contractId") != null) {
                args.put("contractId", lookup.single().get("contractId"));
                changed = true;
            }
        }

        if (candidates != null) {
            String question = "找到多个" + candidateLabel + "候选："
                    + candidateNames(candidates) + "。请明确是哪一个？";
            return new RouteDecision(capabilityId, args, route.confidence(), true,
                    question, route.reason(), List.of(), route.missingFields(), route.router());
        }
        if (!changed) {
            return route;
        }
        // 只读解析已经补齐关键 ID；剩余字段交给 schema 校验/自修复，不在这里继续追问。
        return new RouteDecision(capabilityId, args, Math.max(route.confidence(), 0.9),
                false, null, route.reason(), List.of(), List.of(), route.router());
    }

    private Lookup lookup(String capabilityId, Map<String, Object> input,
                          AuthUser user, String traceId) {
        Capability capability = registry.capability(capabilityId).orElse(null);
        if (capability == null) {
            return Lookup.none();
        }
        try {
            policyService.requireCapability(user, capability.capabilityId());
        } catch (Exception ex) {
            return Lookup.none();
        }
        TaskRecord task = taskService.submit("resolve:" + user.id(), capability,
                input, user.actorId(), traceId);
        if (task.status != TaskStatus.SUCCEEDED || task.output == null) {
            return Lookup.none();
        }
        if (task.output instanceof List<?> list) {
            if (list.size() == 1 && list.get(0) instanceof Map<?, ?> map) {
                return new Lookup(map, list);
            }
            return new Lookup(null, list);
        }
        if (task.output instanceof Map<?, ?> map) {
            return new Lookup(map, List.of(map));
        }
        return Lookup.none();
    }

    private String candidateNames(List<?> items) {
        List<String> names = new ArrayList<>();
        for (Object item : items.stream().limit(5).toList()) {
            if (item instanceof Map<?, ?> map) {
                Object no = map.get("parcelNo") != null ? map.get("parcelNo") : map.get("ticketNo");
                Object name = map.get("parcelName") != null ? map.get("parcelName") : map.get("title");
                names.add(String.valueOf(no) + (name == null ? "" : "（" + name + "）"));
            }
        }
        return String.join("、", names);
    }

    private static boolean blank(Object value) {
        return value == null || String.valueOf(value).isBlank();
    }

    private record Lookup(Map<?, ?> single, List<?> items) {
        static Lookup none() {
            return new Lookup(null, List.of());
        }
    }

    private void handleMainTool(RouteDecision route, AuthUser user, SessionState session,
                                String content, SseEmitter emitter) {
        String capabilityId = route.capabilityId();
        Object output;
        String answer;
        if (MainAgentTools.BATCH_EXECUTE.equals(capabilityId)) {
            handleBatchTool(route, user, session, content, emitter);
            return;
        }
        if (MainAgentTools.PLATFORM_CATALOG.equals(capabilityId)) {
            List<AgentCatalogService.AgentView> agents = new ArrayList<>(catalogService.forUser(user));
            String agentKey = route.args() == null ? null : str(route.args().get("agentKey"));
            String keyword = route.args() == null ? null : str(route.args().get("keyword"));
            // “具体是什么功能/全部列出来”这类追问，沿用上一轮聚焦的 Agent。
            if (blank(agentKey) && blank(keyword) && isCatalogFollowUp(content)) {
                agentKey = lastCatalogAgent.get(session.sessionNo);
            }
            if (!blank(agentKey)) {
                String wanted = agentKey;
                List<AgentCatalogService.AgentView> exact = agents.stream()
                        .filter(agent -> wanted.equals(agent.agentKey()))
                        .toList();
                agents = exact.isEmpty()
                        ? agents.stream().filter(agent -> matchesAgent(agent, wanted)).toList()
                        : exact;
            } else if (!blank(keyword)) {
                String kw = keyword;
                List<AgentCatalogService.AgentView> matched = agents.stream()
                        .filter(agent -> matchesAgent(agent, kw))
                        .toList();
                if (!matched.isEmpty()) {
                    agents = matched;
                }
            }
            if (agents.size() == 1) {
                lastCatalogAgent.put(session.sessionNo, agents.get(0).agentKey());
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("agentCount", agents.size());
            data.put("capabilityCount", agents.stream()
                    .mapToInt(agent -> agent.capabilities().size()).sum());
            data.put("agents", agents);
            output = data;
            if (agents.isEmpty()) {
                answer = "没有找到匹配的 Agent（可能名称不对，或当前账号无权查看）。";
            } else if (agents.size() == 1) {
                AgentCatalogService.AgentView agent = agents.get(0);
                answer = agent.displayName() + "（" + agent.agentKey() + "）有 "
                        + agent.capabilities().size() + " 项能力："
                        + capabilityDetails(agent) + "。原始目录见下方结果。";
            } else {
                answer = "当前匹配到 " + agents.size() + " 个 Agent："
                        + String.join("、", agents.stream()
                        .map(agent -> agent.displayName() + "（" + agent.capabilities().size() + " 项能力）")
                        .toList())
                        + "。可以指定 Agent 名称继续查看。";
            }
        } else if (MainAgentTools.PLATFORM_A2A.equals(capabilityId)) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("protocol", "agent-task-v1");
            data.put("a2aCompatibility", "discovery-only");
            data.put("agentCard", true);
            data.put("jsonRpc", false);
            data.put("pushNotifications", false);
            data.put("sse", true);
            data.put("humanConfirmation", true);
            data.put("cancel", true);
            data.put("agentCount", registry.descriptors().size());
            data.put("capabilityCount", registry.capabilities().size());
            data.put("dynamicAgents", dynamicAgentRegistry.agentKeys());
            output = data;
            answer = "A2A 当前是 discovery-only：Agent Card、任务/确认/事件语义已落地；"
                    + "JSON-RPC 与 push notification 未实现，保留 transport facade 演进空间。";
        } else if (MainAgentTools.PLATFORM_MEMORY.equals(capabilityId)) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("sessionSummary", session.summary == null ? "" : session.summary);
            data.put("profile", userProfileService.getProfile(user.id()));
            if (userProfileService.getMeta(user.id()) != null) {
                data.put("meta", userProfileService.getMeta(user.id()));
            }
            output = data;
            answer = session.summary == null || session.summary.isBlank()
                    ? "当前会话还没有生成摘要；全局画像见下方结果。"
                    : "当前会话摘要和全局画像如下。";
        } else if (MainAgentTools.MEMORY_UPDATE.equals(capabilityId)) {
            Map<String, Object> profile = userProfileService.merge(user.id(), route.args());
            output = profile;
            answer = "已记住你的偏好，原始画像如下。";
        } else {
            Map<String, Object> profile = userProfileService.getProfile(user.id());
            output = profile;
            answer = profile.isEmpty() ? "目前还没有记住你的偏好。" : "这是你的当前画像。";
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("capabilityId", capabilityId);
        metadata.put("agentKey", "main-agent");
        metadata.put("mainTool", true);
        ChatMessage assistant = store.addMessage(session, "ASSISTANT", answer, "RESULT", metadata, null);
        emit(emitter, "delta", Map.of("content", answer));
        emit(emitter, "result", Map.of(
                "taskNo", "memory:" + assistant.messageNo(),
                "capabilityId", capabilityId,
                "output", output));
        emit(emitter, "done", Map.of("messageNo", assistant.messageNo(), "status", "SUCCEEDED"));
        emitter.complete();
        memoryService.updateAsync(session.sessionNo);
    }

    /**
     * 批量执行：一次请求里包含多个独立操作时，逐项解析 ID、逐项建任务。
     * 写操作仍然逐项人工确认；不会自动确认。
     */
    private void handleBatchTool(RouteDecision route, AuthUser user, SessionState session,
                                 String content, SseEmitter emitter) {
        Object rawItems = route.args() == null ? null : route.args().get("items");
        List<?> source = rawItems instanceof List<?> list ? list : List.of();
        if (source.isEmpty()) {
            String message = "批量执行需要至少一个操作项。";
            ChatMessage assistant = store.addMessage(session, "ASSISTANT", message,
                    "TEXT", Map.of("batch", true), null);
            emit(emitter, "delta", Map.of("content", message));
            emit(emitter, "done", Map.of("messageNo", assistant.messageNo(), "status", "CLARIFIED"));
            emitter.complete();
            return;
        }
        List<?> items = source.size() > 5 ? source.subList(0, 5) : source;
        List<TaskRecord> tasks = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        int index = 0;

        for (Object rawItem : items) {
            index++;
            if (!(rawItem instanceof Map<?, ?> item)) {
                failures.add("第" + index + "项格式不正确");
                continue;
            }
            String capabilityId = str(item.get("capabilityId"));
            Map<String, Object> args = mapOf(item.get("args"));
            Capability capability = registry.capability(capabilityId).orElse(null);
            if (capability == null) {
                failures.add("第" + index + "项：能力不存在 " + capabilityId);
                continue;
            }
            try {
                policyService.requireCapability(user, capability.capabilityId());
            } catch (Exception ex) {
                failures.add("第" + index + "项：无权使用 " + capabilityId);
                continue;
            }

            String keyword = first(args, "keyword", "description", "title", "query", content);
            try {
                if (capabilityId.startsWith("property.ticket.")
                        && !"property.ticket.query".equals(capabilityId)
                        && !"property.ticket.create".equals(capabilityId)
                        && blank(args.get("ticketNo"))) {
                    Lookup lookup = lookup("property.ticket.query",
                            Map.of("keyword", keyword), user, ids.traceId());
                    if (lookup.single() != null) {
                        args.put("ticketNo", lookup.single().get("ticketNo"));
                    } else if (lookup.items().size() > 1) {
                        failures.add("第" + index + "项：找到多个工单候选 "
                                + candidateNames(lookup.items()) + "，请明确");
                        continue;
                    } else {
                        failures.add("第" + index + "项：没有找到对应工单");
                        continue;
                    }
                }
                if ("property.ticket.assign".equals(capabilityId) && blank(args.get("assigneeId"))) {
                    String workerName = first(args, "assigneeName", "workerName", "assignee", "");
                    String workerId = workerId(workerName);
                    if (workerId != null) {
                        args.put("assigneeId", workerId);
                    }
                }
                if ("gis.patrol.create".equals(capabilityId) && blank(args.get("parcelNo"))) {
                    Lookup lookup = lookup("gis.parcel.query",
                            Map.of("keyword", keyword), user, ids.traceId());
                    if (lookup.single() != null) {
                        args.put("parcelNo", lookup.single().get("parcelNo"));
                    } else {
                        failures.add("第" + index + "项：没有唯一匹配的地块");
                        continue;
                    }
                }
                if ("contract.review".equals(capabilityId)
                        && blank(args.get("contractId")) && blank(args.get("contractNo"))) {
                    Lookup lookup = lookup("contract.resolve",
                            Map.of("query", keyword), user, ids.traceId());
                    if (lookup.single() != null && lookup.single().get("contractId") != null) {
                        args.put("contractId", lookup.single().get("contractId"));
                    } else {
                        failures.add("第" + index + "项：没有解析到合同");
                        continue;
                    }
                }

                TaskRecord task = taskService.submit(session.sessionNo, capability, args,
                        user.actorId(), ids.traceId());
                RecoveryOutcome recovery = recoveryService.recover(session.sessionNo, content,
                        capability, args, task, user.actorId(), ids.traceId());
                task = recovery.task();
                if (recovery.clarification()) {
                    failures.add("第" + index + "项：" + recovery.message());
                    continue;
                }
                tasks.add(task);
            } catch (Exception ex) {
                failures.add("第" + index + "项执行失败：" + ex.getMessage());
            }
        }

        emit(emitter, "batch", Map.of(
                "count", items.size(),
                "created", tasks.size(),
                "failed", failures.size()));

        int taskIndex = 0;
        for (TaskRecord task : tasks) {
            taskIndex++;
            Map<String, Object> view = taskEvent(task);
            view.put("batchIndex", taskIndex);
            view.put("batchCount", tasks.size());
            emit(emitter, "task", view);
            for (TaskEvent event : task.events) {
                emit(emitter, "task_event", eventView(task.taskNo, event));
            }
            if (task.status == TaskStatus.WAITING_CONFIRM && task.confirmation != null) {
                emit(emitter, "confirm", Map.of(
                        "taskNo", task.taskNo,
                        "confirmation", confirmationView(task),
                        "batchIndex", taskIndex));
            }
            if (task.status == TaskStatus.SUCCEEDED) {
                emit(emitter, "result", Map.of(
                        "taskNo", task.taskNo,
                        "capabilityId", task.capabilityId,
                        "output", task.output == null ? Map.of() : task.output));
            }
        }

        StringBuilder summary = new StringBuilder();
        if (tasks.isEmpty()) {
            summary.append("没有创建任务。");
        } else {
            summary.append("已创建 ").append(tasks.size()).append(" 个任务");
            boolean waiting = tasks.stream().anyMatch(task -> task.status == TaskStatus.WAITING_CONFIRM);
            summary.append(waiting ? "，请分别确认。" : "。");
        }
        if (!failures.isEmpty()) {
            summary.append(" 有 ").append(failures.size()).append(" 项需要补充：")
                    .append(String.join("；", failures));
        }

        ChatMessage assistant = store.addMessage(session, "ASSISTANT", summary.toString(),
                "TASK", Map.of("batch", true, "created", tasks.size(),
                        "failed", failures.size()), null);
        emit(emitter, "delta", Map.of("content", summary.toString()));
        emit(emitter, "done", Map.of(
                "messageNo", assistant.messageNo(),
                "status", tasks.isEmpty() ? "CLARIFIED" : "BATCH"));
        emitter.complete();
        memoryService.updateAsync(session.sessionNo);
    }

    private boolean isCatalogFollowUp(String content) {
        return containsAny(content, "具体", "全部功能", "所有功能", "列出来",
                "详细", "还有什么", "它的功能", "这个agent", "看看功能");
    }

    private boolean matchesAgent(AgentCatalogService.AgentView agent, String keyword) {
        if (agent.agentKey().contains(keyword) || agent.displayName().contains(keyword)) {
            return true;
        }
        return agent.capabilities().stream()
                .anyMatch(capability -> capability.capabilityId().contains(keyword)
                        || capability.displayName().contains(keyword));
    }

    private String capabilityDetails(AgentCatalogService.AgentView agent) {
        return String.join("、", agent.capabilities().stream()
                .map(capability -> capability.displayName()
                        + "（" + (capability.readOnly() ? "只读" : "写操作")
                        + (capability.requiresConfirmation() ? "，需确认" : "") + "）")
                .toList());
    }

    private static boolean containsAny(String text, String... keywords) {
        if (text == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String workerId(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (Map<String, Object> worker : optionCatalogService.optionList("workers")) {
            if (name.equals(String.valueOf(worker.get("name")))) {
                return String.valueOf(worker.get("id"));
            }
        }
        return null;
    }

    private static Map<String, Object> mapOf(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return new LinkedHashMap<>();
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
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
                RecoveryOutcome recovery = recoveryService.recover(sessionNo, content, pre,
                        step.args() == null ? Map.of() : step.args(), preTask, user.actorId(), traceId);
                if (recovery.clarification() || recovery.task().status != TaskStatus.SUCCEEDED) {
                    return new ExecutionResult(recovery.capability(), recovery.task(), recovery);
                }
                mergeOutput(args, recovery.task().output);
                continue;
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

        // 主 Agent 记忆层：运行时安装的 Agent 不各自维护记忆，
        // 由主 Agent 注入当前会话摘要 + 全局画像，子 Agent 只消费。
        if (dynamicAgentRegistry.isDynamic(capability.agentKey())) {
            Map<String, Object> memory = new LinkedHashMap<>();
            SessionState current = store.getSession(sessionNo);
            if (current != null && current.summary != null && !current.summary.isBlank()) {
                memory.put("sessionSummary", current.summary);
            }
            String profile = userProfileService.contextFor(user.id());
            if (profile != null && !profile.isBlank()) {
                memory.put("userProfile", profile);
            }
            if (!memory.isEmpty()) {
                args.put("_memory", memory);
            }
        }

        TaskRecord task = taskService.submit(sessionNo, capability, args, user.actorId(), traceId);
        RecoveryOutcome recovery = recoveryService.recover(sessionNo, content, capability,
                args, task, user.actorId(), traceId);
        return new ExecutionResult(recovery.capability(), recovery.task(), recovery);
    }

    private void mergeOutput(Map<String, Object> args, Object output) {
        if (output instanceof Map<?, ?> map) {
            mergeMap(args, map);
            return;
        }
        // 只读查询返回唯一候选时，自动把业务 ID 合并进最终参数；
        // 多个候选不猜，交给 LLM/追问。
        if (output instanceof List<?> list && list.size() == 1
                && list.get(0) instanceof Map<?, ?> map) {
            mergeMap(args, map);
        }
    }

    private void mergeMap(Map<String, Object> args, Map<?, ?> map) {
        for (String key : List.of("contactName", "contactPhone", "projectId", "buildingId",
                "roomId", "customerId", "ticketNo", "description", "locationText",
                "assigneeId", "contractId", "contractNo", "parcelNo", "reportNo", "planNo",
                "changeNo")) {
            Object value = map.get(key);
            if (value != null) {
                args.putIfAbsent(key, value);
            }
        }
        // 只读查询常见包络：{items:[...]} 或 {result:[...]}；唯一候选时继续合并。
        Object items = map.get("items");
        if (items == null) {
            items = map.get("result");
        }
        if (items instanceof List<?> list && list.size() == 1
                && list.get(0) instanceof Map<?, ?> item) {
            mergeMap(args, item);
        }
    }

    private record ExecutionResult(Capability capability, TaskRecord task, RecoveryOutcome recovery) {
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
