package com.wuye.agent.task;

import com.wuye.agent.agent.adapter.AgentAdapter;
import com.wuye.agent.agent.adapter.AdapterRegistry;
import com.wuye.agent.agent.catalog.InputSchemaValidator;
import com.wuye.agent.agent.model.AdapterResult;
import com.wuye.agent.agent.model.AdapterTaskRequest;
import com.wuye.agent.agent.model.AgentError;
import com.wuye.agent.agent.model.Capability;
import com.wuye.agent.agent.model.Confirmation;
import com.wuye.agent.agent.model.Decision;
import com.wuye.agent.agent.model.TaskEvent;
import com.wuye.agent.agent.model.TaskStatus;
import com.wuye.agent.audit.AuditService;
import com.wuye.agent.auth.AuthContext;
import com.wuye.agent.auth.AuthUser;
import com.wuye.agent.common.ApiException;
import com.wuye.agent.event.EventFactory;
import com.wuye.agent.event.EventPublisher;
import com.wuye.agent.event.EventTypes;
import com.wuye.agent.human.HumanInterventionService;
import com.wuye.agent.common.Ids;
import com.wuye.agent.store.Store;
import com.wuye.agent.store.TaskRecord;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 任务网关与状态机：
 * 提交 -> 归一化结果 -> 确认 -> 取消 -> 事件续传。
 *
 * <p>所有子 Agent 调用都经 Adapter；本类不感知方言，也不直连子 Agent 存储。</p>
 */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private final Store store;
    private final AdapterRegistry registry;
    private final InputSchemaValidator schemaValidator;
    private final AuditService auditService;
    private final HumanInterventionService humanInterventionService;
    private final Ids ids;
    private final EventPublisher eventPublisher;
    private final EventFactory eventFactory;

    /** 兼容旧测试/手工构造：关闭事件出口。 */
    public TaskService(Store store, AdapterRegistry registry, InputSchemaValidator schemaValidator,
                       AuditService auditService, HumanInterventionService humanInterventionService,
                       Ids ids) {
        this(store, registry, schemaValidator, auditService, humanInterventionService, ids,
                EventPublisher.noop(), new EventFactory(ids, "wuye-agent-bff"));
    }

    @Autowired
    public TaskService(Store store, AdapterRegistry registry, InputSchemaValidator schemaValidator,
                       AuditService auditService, HumanInterventionService humanInterventionService,
                       Ids ids, EventPublisher eventPublisher, EventFactory eventFactory) {
        this.store = store;
        this.registry = registry;
        this.schemaValidator = schemaValidator;
        this.auditService = auditService;
        this.humanInterventionService = humanInterventionService;
        this.ids = ids;
        this.eventPublisher = eventPublisher == null ? EventPublisher.noop() : eventPublisher;
        this.eventFactory = eventFactory;
    }

    public TaskRecord submit(String sessionNo, Capability capability, Map<String, Object> input,
                             String actorId, String traceId) {
        return submit(sessionNo, capability, input, actorId, traceId, null);
    }

    /**
     * 带 Skill 的任务提交。skillId 只做任务选择封装，不替代能力白名单与子 Agent 鉴权。
     */
    public TaskRecord submit(String sessionNo, Capability capability, Map<String, Object> input,
                             String actorId, String traceId, String skillId) {
        AgentAdapter adapter = registry.get(capability.agentKey());
        if (adapter == null) {
            throw ApiException.notFound("AGENT_NOT_FOUND", "子 Agent 未注册：" + capability.agentKey());
        }

        String taskNo = ids.next("wt_");
        String clientTaskId = ids.next("wuye-");
        String executionMode = capability.supportsSync() ? "SYNC" : "ASYNC";
        TaskRecord task = new TaskRecord(taskNo, sessionNo, capability.agentKey(), capability.capabilityId(),
                clientTaskId, actorId, executionMode,
                input == null ? Map.of() : input, Instant.now());
        task.traceId = traceId;
        task.tenantId = currentTenantId();
        task.skillId = skillId;
        store.putTask(task);
        publishRequested(task, traceId);

        // LLM/用户参数先过最小 schema：缺必填/类型不对时不调用子 Agent，
        // 由上层自修复服务转成追问或修正，而不是把技术错误抛给用户。
        InputSchemaValidator.Result validation =
                schemaValidator.validate(capability.capabilityId(), task.input);
        if (!validation.valid()) {
            task.status = TaskStatus.FAILED;
            task.error = AgentError.validation(validation.message());
            task.currentStep = "VALIDATION_FAILED";
            task.finishedAt = Instant.now();
            addSystemEvent(task, "ERROR", TaskStatus.FAILED, Map.of(
                    "message", validation.message(),
                    "missingFields", validation.fields()));
            store.putTask(task);
            humanInterventionService.upsertPending(task.taskNo);
            auditService.record("TASK_SUBMIT", "TASK", task.taskNo, task.agentKey, task.capabilityId,
                    task.status.name(), Map.of("executionMode", executionMode, "validation", "FAILED"));
            publishTerminal(task, traceId);
            return task;
        }

        AdapterTaskRequest request = new AdapterTaskRequest(
                capability.capabilityId(), capability.taskType(), task.input, clientTaskId,
                executionMode, actorId, traceId, traceId, skillId);
        try {
            apply(task, adapter.submit(request), traceId);
        } catch (Exception ex) {
            apply(task, AdapterResult.failed(task.childTaskId,
                    AgentError.dependency("子 Agent 调用失败：" + ex.getMessage()), List.of()), traceId);
        }
        auditService.record("TASK_SUBMIT", "TASK", task.taskNo, task.agentKey, task.capabilityId,
                task.status.name(), Map.of("executionMode", executionMode));
        return task;
    }

    public TaskRecord get(String taskNo) {
        TaskRecord task = requireTask(taskNo);
        refresh(task);
        return task;
    }

    public List<TaskRecord> list(String sessionNo) {
        return store.listTasks(sessionNo);
    }

    public TaskRecord confirm(String taskNo, Decision decision, String comment,
                              String actorId, boolean admin, String traceId) {
        TaskRecord task = get(taskNo);
        if (!admin && !task.actorId.equals(actorId)) {
            throw new ApiException("AUTH_FORBIDDEN", 403, "只能由任务发起人确认");
        }
        if (task.status != TaskStatus.WAITING_CONFIRM) {
            throw ApiException.conflict("TASK_STATE_CONFLICT", "任务当前状态不允许确认：" + task.status);
        }
        Confirmation confirmation = task.confirmation;
        if (confirmation == null || confirmation.token() == null) {
            throw ApiException.conflict("CONFIRM_REQUIRED", "任务缺少确认令牌");
        }
        if (confirmation.expired()) {
            task.status = TaskStatus.FAILED;
            task.error = AgentError.timeout("确认令牌已过期");
            task.confirmation = confirmation.withStatus("EXPIRED");
            task.confirmationStatus = "EXPIRED";
            task.finishedAt = Instant.now();
            store.removeConfirmationToken(confirmation.token());
            addSystemEvent(task, "ERROR", TaskStatus.FAILED, Map.of("message", "确认令牌已过期"));
            store.putTask(task);
            publishTerminal(task, traceId);
            return task;
        }

        AgentAdapter adapter = registry.get(task.agentKey);
        if (adapter == null) {
            throw ApiException.notFound("AGENT_NOT_FOUND", "子 Agent 未注册：" + task.agentKey);
        }

        AdapterResult result = adapter.confirm(task.childTaskId, confirmation.token(), decision,
                comment, task.actorId, traceId);
        task.traceId = traceId;
        apply(task, result, traceId);
        task.confirmationStatus = decision == Decision.APPROVE ? "APPROVED" : "REJECTED";
        if (task.confirmation != null) {
            task.confirmation = task.confirmation.withStatus(task.confirmationStatus);
        }
        store.removeConfirmationToken(confirmation.token());
        if (task.status.terminal()) {
            task.finishedAt = Instant.now();
        }
        store.putTask(task);
        auditService.record("TASK_CONFIRM", "TASK", task.taskNo, task.agentKey, task.capabilityId,
                task.status.name(), Map.of("decision", decision.name()));
        return task;
    }

    public TaskRecord cancel(String taskNo, String reason, String actorId, boolean admin, String traceId) {
        TaskRecord task = get(taskNo);
        if (!admin && !task.actorId.equals(actorId)) {
            throw new ApiException("AUTH_FORBIDDEN", 403, "只能由任务发起人取消");
        }
        if (task.status.terminal()) {
            throw ApiException.conflict("TASK_STATE_CONFLICT", "任务已终态：" + task.status);
        }
        if (!task.cancelSupported) {
            throw ApiException.conflict("TASK_STATE_CONFLICT", "该 Agent 不支持取消");
        }
        AgentAdapter adapter = registry.get(task.agentKey);
        if (adapter == null) {
            throw ApiException.notFound("AGENT_NOT_FOUND", "子 Agent 未注册：" + task.agentKey);
        }
        AdapterResult result = adapter.cancel(task.childTaskId, reason, task.actorId, traceId);
        task.traceId = traceId;
        apply(task, result, traceId);
        task.cancelReason = reason;
        if (task.confirmation != null) {
            task.confirmation = task.confirmation.withStatus("CANCELLED");
            task.confirmationStatus = "CANCELLED";
            store.removeConfirmationToken(task.confirmation.token());
        }
        store.putTask(task);
        auditService.record("TASK_CANCEL", "TASK", task.taskNo, task.agentKey, task.capabilityId,
                task.status.name(), Map.of("reason", reason == null ? "" : reason));
        return task;
    }

    public List<TaskEvent> events(String taskNo, long afterSeq) {
        TaskRecord task = get(taskNo);
        return task.events.stream().filter(e -> e.seq() > afterSeq).toList();
    }

    private void refresh(TaskRecord task) {
        if (task.childTaskId == null || task.status.terminal()) {
            return;
        }
        AgentAdapter adapter = registry.get(task.agentKey);
        if (adapter == null) {
            return;
        }
        AdapterResult result = adapter.getTask(task.childTaskId, task.actorId, null);
        if (result != null) {
            apply(task, result, task.traceId);
        }
    }

    private void apply(TaskRecord task, AdapterResult result, String traceId) {
        TaskStatus previous = task.status;
        if (result == null) {
            task.status = TaskStatus.FAILED;
            task.error = AgentError.dependency("子 Agent 未返回结果");
            task.finishedAt = Instant.now();
            store.putTask(task);
            publishTransition(task, previous, traceId);
            return;
        }
        if (result.childTaskId() != null) {
            task.childTaskId = result.childTaskId();
        }
        if (result.output() != null) {
            task.output = result.output();
        }
        if (result.error() != null) {
            task.error = result.error();
        }
        task.status = normalize(result.status(), result.error());
        if (result.progress() != null) {
            task.progress = result.progress();
        }
        if (result.currentStep() != null) {
            task.currentStep = result.currentStep();
        }
        task.cancelSupported = result.cancelSupported();
        if (result.confirmation() != null) {
            task.confirmation = result.confirmation();
            if ("NONE".equals(task.confirmationStatus)) {
                task.confirmationStatus = "PENDING";
            }
            store.putConfirmationToken(result.confirmation().token(), task);
        }
        if (task.status == TaskStatus.RUNNING && task.startedAt == null) {
            task.startedAt = Instant.now();
        }
        if (task.status.terminal()) {
            task.finishedAt = Instant.now();
        }
        addEvents(task, result.events());
        store.putTask(task);
        if (task.status == TaskStatus.WAITING_CONFIRM
                || task.status == TaskStatus.FAILED
                || task.status == TaskStatus.REJECTED) {
            humanInterventionService.upsertPending(task.taskNo);
        } else if (task.status == TaskStatus.SUCCEEDED
                || task.status == TaskStatus.CANCELLED) {
            humanInterventionService.autoResolve(task.taskNo, "任务已结束：" + task.status);
        }
        publishTransition(task, previous, traceId);
    }

    // ------------------------------------------------------------------
    // 事件出口：只发“状态迁移”，轮询刷新不会重复发终态事件。
    // 事件故障被 InMemoryEventBus 隔离，绝不影响任务主流程。
    // ------------------------------------------------------------------

    private void publishRequested(TaskRecord task, String traceId) {
        Map<String, Object> payload = basePayload(task);
        payload.put("executionMode", task.executionMode);
        payload.put("input", task.input);
        publish(EventTypes.TASK_REQUESTED, task, traceId, payload);
    }

    private void publishTransition(TaskRecord task, TaskStatus previous, String traceId) {
        if (previous == task.status) {
            return;
        }
        if (task.status == TaskStatus.WAITING_CONFIRM) {
            publishConfirmationRequested(task, traceId);
        } else if (task.status.terminal()) {
            publishTerminal(task, traceId);
        }
    }

    private void publishConfirmationRequested(TaskRecord task, String traceId) {
        Map<String, Object> payload = basePayload(task);
        Confirmation confirmation = task.confirmation;
        if (confirmation != null) {
            // 只暴露确认卡展示字段；token 永不进入事件。
            payload.put("title", confirmation.title());
            payload.put("riskLevel", confirmation.riskLevel());
            payload.put("expiresAt", confirmation.expiresAt());
            payload.put("confirmationStatus", task.confirmationStatus);
        }
        publish(EventTypes.TASK_CONFIRMATION_REQUESTED, task, traceId, payload);
    }

    private void publishTerminal(TaskRecord task, String traceId) {
        String eventType = switch (task.status) {
            case SUCCEEDED -> EventTypes.TASK_COMPLETED;
            case CANCELLED -> EventTypes.TASK_CANCELLED;
            default -> EventTypes.TASK_FAILED;
        };
        Map<String, Object> payload = basePayload(task);
        payload.put("status", task.status.name());
        payload.put("durationMs", durationMs(task));
        payload.put("input", task.input);
        if (task.output != null) {
            payload.put("output", task.output);
        }
        if (task.error != null) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", task.error.code());
            error.put("category", task.error.category());
            error.put("retryable", task.error.retryable());
            error.put("message", task.error.message());
            payload.put("error", error);
        }
        if (task.status == TaskStatus.CANCELLED) {
            payload.put("reason", task.cancelReason);
        }
        publish(eventType, task, traceId, payload);
    }

    private Map<String, Object> basePayload(TaskRecord task) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskNo", task.taskNo);
        payload.put("sessionNo", task.sessionNo);
        payload.put("agentKey", task.agentKey);
        payload.put("capabilityId", task.capabilityId);
        payload.put("actorId", task.actorId);
        payload.put("tenantId", task.tenantId);
        payload.put("childTaskId", task.childTaskId);
        payload.put("skillId", task.skillId);
        return payload;
    }

    private void publish(String eventType, TaskRecord task, String traceId,
                         Map<String, Object> payload) {
        try {
            eventPublisher.publish(eventFactory.create(
                    eventType,
                    task.taskNo,
                    eventType + ":" + task.taskNo,
                    traceId == null ? task.traceId : traceId,
                    task.actorId,
                    task.tenantId,
                    payload));
        } catch (Exception ex) {
            // 防御性兜底：即便自定义 Publisher 未遵守约定，也不能让事件拖垮任务。
            log.warn("publish event failed type={} task={}", eventType, task.taskNo, ex);
        }
    }

    private long durationMs(TaskRecord task) {
        Instant start = task.startedAt != null ? task.startedAt : task.createdAt;
        Instant end = task.finishedAt != null ? task.finishedAt : Instant.now();
        return Math.max(0, Duration.between(start, end).toMillis());
    }

    private String currentTenantId() {
        AuthUser user = AuthContext.get();
        if (user == null || user.tenantId() == null || user.tenantId().isBlank()) {
            return "0";
        }
        return user.tenantId();
    }

    private void addEvents(TaskRecord task, List<TaskEvent> incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return;
        }
        long lastChildSeq = task.childEventSeq.get();
        for (TaskEvent event : incoming) {
            if (event.seq() <= lastChildSeq) {
                continue;
            }
            task.childEventSeq.set(event.seq());
            Map<String, Object> payload = new LinkedHashMap<>(event.payload() == null ? Map.of() : event.payload());
            payload.put("childSeq", event.seq());
            Instant createdAt = event.createdAt() == null ? Instant.now() : event.createdAt();
            task.events.add(new TaskEvent(task.eventSeq.incrementAndGet(), event.type(), event.status(),
                    payload, createdAt));
        }
    }

    private void addSystemEvent(TaskRecord task, String type, TaskStatus status, Map<String, Object> payload) {
        task.events.add(new TaskEvent(task.eventSeq.incrementAndGet(), type, status,
                payload == null ? Map.of() : payload, Instant.now()));
    }

    private TaskStatus normalize(TaskStatus status, AgentError error) {
        if (status == TaskStatus.FAILED && error != null
                && ("USER_REJECTED".equals(error.category()) || "CONFIRM_REJECTED".equals(error.code()))) {
            return TaskStatus.REJECTED;
        }
        return status;
    }

    private TaskRecord requireTask(String taskNo) {
        TaskRecord task = store.getTask(taskNo);
        if (task == null) {
            throw ApiException.notFound("TASK_NOT_FOUND", "任务不存在：" + taskNo);
        }
        return task;
    }
}
