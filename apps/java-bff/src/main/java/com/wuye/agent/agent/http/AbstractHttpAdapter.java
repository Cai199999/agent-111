package com.wuye.agent.agent.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.wuye.agent.agent.adapter.AgentAdapter;
import com.wuye.agent.agent.model.AdapterResult;
import com.wuye.agent.agent.model.AdapterTaskRequest;
import com.wuye.agent.agent.model.AgentDescriptor;
import com.wuye.agent.agent.model.AgentError;
import com.wuye.agent.agent.model.AgentHealth;
import com.wuye.agent.agent.model.Confirmation;
import com.wuye.agent.agent.model.Decision;
import com.wuye.agent.agent.model.TaskEvent;
import com.wuye.agent.agent.model.TaskStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 真实 HTTP Adapter 的公共实现：提交/查询/确认/取消/事件/健康。
 *
 * <p>方言差异（包络、错误码、字段名）在本类按 AgentDialect 处理；
 * 子类只提供 descriptor 与 endpoint。</p>
 */
public abstract class AbstractHttpAdapter implements AgentAdapter {

    protected final HttpAgentSupport support;
    protected final AgentHttpProperties.Endpoint endpoint;
    protected final AgentDialect dialect;
    protected final AgentDescriptor descriptor;

    protected AbstractHttpAdapter(HttpAgentSupport support,
                                  AgentHttpProperties.Endpoint endpoint,
                                  AgentDialect dialect,
                                  AgentDescriptor descriptor) {
        this.support = support;
        this.endpoint = endpoint;
        this.dialect = dialect;
        this.descriptor = descriptor;
    }

    @Override
    public AgentDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public AdapterResult submit(AdapterTaskRequest request) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("taskType", request.taskType());
            body.put("input", request.input());
            body.put("clientTaskId", request.clientTaskId());
            body.put("executionMode", request.executionMode());
            if (request.correlationId() != null) {
                body.put("correlationId", request.correlationId());
            }
            HttpAgentSupport.HttpResult result = support.post(endpoint, "/agent/v1/tasks", body,
                    request.actorId(), request.traceId(), true);
            return parseTask(result, null);
        } catch (Exception ex) {
            return failure(null, AgentError.dependency("提交任务失败：" + ex.getMessage()));
        }
    }

    @Override
    public AdapterResult getTask(String childTaskId) {
        return getTask(childTaskId, endpoint.getActorId(), null);
    }

    @Override
    public AdapterResult getTask(String childTaskId, String actorId, String traceId) {
        try {
            HttpAgentSupport.HttpResult result = support.get(endpoint,
                    "/agent/v1/tasks/" + childTaskId, actor(actorId), traceId, true);
            AdapterResult parsed = parseTask(result, childTaskId);
            if (parsed.events() == null || parsed.events().isEmpty()) {
                List<TaskEvent> events = events(childTaskId, 0);
                if (!events.isEmpty()) {
                    parsed = new AdapterResult(parsed.childTaskId(), parsed.status(), parsed.output(),
                            parsed.error(), parsed.confirmation(), parsed.progress(),
                            parsed.currentStep(), parsed.cancelSupported(), events);
                }
            }
            return parsed;
        } catch (Exception ex) {
            return failure(childTaskId, AgentError.dependency("查询任务失败：" + ex.getMessage()));
        }
    }

    @Override
    public AdapterResult confirm(String childTaskId, String token, Decision decision, String comment) {
        return confirm(childTaskId, token, decision, comment, endpoint.getActorId(), null);
    }

    @Override
    public AdapterResult confirm(String childTaskId, String token, Decision decision,
                                 String comment, String actorId, String traceId) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("confirmationToken", token);
            body.put("decision", decision.name());
            if (comment != null) {
                body.put("comment", comment);
            }
            HttpAgentSupport.HttpResult result = support.post(endpoint,
                    "/agent/v1/tasks/" + childTaskId + "/confirm", body,
                    actor(actorId), traceId, true);
            return parseTask(result, childTaskId);
        } catch (Exception ex) {
            return failure(childTaskId, AgentError.dependency("确认失败：" + ex.getMessage()));
        }
    }

    @Override
    public AdapterResult cancel(String childTaskId, String reason) {
        return cancel(childTaskId, reason, endpoint.getActorId(), null);
    }

    @Override
    public AdapterResult cancel(String childTaskId, String reason, String actorId, String traceId) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("reason", reason == null ? "用户取消" : reason);
            HttpAgentSupport.HttpResult result = support.post(endpoint,
                    "/agent/v1/tasks/" + childTaskId + "/cancel", body,
                    actor(actorId), traceId, true);
            return parseTask(result, childTaskId);
        } catch (Exception ex) {
            return failure(childTaskId, AgentError.dependency("取消失败：" + ex.getMessage()));
        }
    }

    private String actor(String actorId) {
        return actorId == null || actorId.isBlank() ? endpoint.getActorId() : actorId;
    }

    @Override
    public List<TaskEvent> events(String childTaskId, long afterSeq) {
        try {
            HttpAgentSupport.HttpResult result = support.get(endpoint,
                    "/agent/v1/tasks/" + childTaskId + "/events?afterSeq=" + afterSeq,
                    endpoint.getActorId(), null, true);
            if (result.status() < 200 || result.status() >= 300) {
                return List.of();
            }
            JsonNode body = support.unwrap(result.body(), dialect);
            return parseEvents(body);
        } catch (Exception ex) {
            return List.of();
        }
    }

    @Override
    public AgentHealth health() {
        long start = System.currentTimeMillis();
        int health = support.ping(endpoint, path(descriptor.healthPath(), "/health"),
                endpoint.getActorId(), null);
        int ready = support.ping(endpoint, path(descriptor.readyPath(), "/ready"),
                endpoint.getActorId(), null);
        long latency = System.currentTimeMillis() - start;
        return new AgentHealth(descriptor.agentKey(),
                health >= 200 && health < 300 ? "UP" : "DOWN",
                ready >= 200 && ready < 300 ? "UP" : "DOWN",
                latency);
    }

    protected AdapterResult parseTask(HttpAgentSupport.HttpResult result, String fallbackTaskId) {
        JsonNode body = support.unwrap(result.body(), dialect);
        if (result.status() < 200 || result.status() >= 300) {
            AgentError error = errorFrom(result, body);
            return failure(fallbackTaskId, error);
        }

        String childTaskId = HttpAgentSupport.text(body, "taskId", "id");
        if (childTaskId == null) {
            childTaskId = fallbackTaskId;
        }
        JsonNode errorNode = body.get("error");
        AgentError error = parseError(errorNode, result.status(), body);
        TaskStatus status = normalizeStatus(HttpAgentSupport.text(body, "status", "PENDING"), error);

        Object output = support.object(first(body.get("output"), body.get("result")));
        Confirmation confirmation = parseConfirmation(body);
        Integer progress = HttpAgentSupport.integer(body, "progress", "percent");
        String currentStep = HttpAgentSupport.text(body, "currentStep", "step");
        boolean cancelSupported = HttpAgentSupport.bool(body, "cancelSupported",
                HttpAgentSupport.bool(body, "cancelable", true));
        List<TaskEvent> events = parseEvents(body);

        return new AdapterResult(childTaskId, status, output, error, confirmation,
                progress, currentStep, cancelSupported, events);
    }

    protected List<TaskEvent> parseEvents(JsonNode body) {
        JsonNode array = body == null ? null : body.get("events");
        if (array == null && body != null) {
            array = body.get("items");
        }
        if (array == null && body != null && body.isArray()) {
            array = body;
        }
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<TaskEvent> events = new ArrayList<>(array.size());
        long index = 0;
        for (JsonNode item : array) {
            index++;
            long seq = item.has("seq") ? item.get("seq").asLong(index) : index;
            String type = HttpAgentSupport.text(item, "type", "eventType", "STATUS_CHANGED");
            String status = HttpAgentSupport.text(item, "status");
            TaskStatus taskStatus = status == null ? null : normalizeStatus(status, null);
            Map<String, Object> payload = support.map(item.get("payload"));
            Instant createdAt = HttpAgentSupport.instant(item, "createdAt");
            if (createdAt == null) {
                createdAt = Instant.now();
            }
            events.add(new TaskEvent(seq, type, taskStatus, payload, createdAt));
        }
        return events;
    }

    protected Confirmation parseConfirmation(JsonNode body) {
        if (body == null || body.isNull()) {
            return null;
        }
        JsonNode node = first(body.get("confirmation"), body.get("confirm"));
        String token = node != null && node.isTextual()
                ? node.asText()
                : HttpAgentSupport.text(node, "confirmationToken", "token");
        if (token == null) {
            token = HttpAgentSupport.text(body, "confirmationToken");
        }

        JsonNode summary = first(body.get("confirmationSummary"),
                node == null ? null : node.get("summary"));
        String title = firstText(node, "title", "summaryTitle");
        if (title == null) {
            title = firstText(summary, "title");
        }
        if (title == null && token != null) {
            title = "操作确认";
        }
        if (title == null) {
            return null;
        }

        String risk = firstText(node, "riskLevel", "risk");
        if (risk == null) {
            risk = firstText(summary, "riskLevel", "risk");
        }
        if (risk == null) {
            risk = "MEDIUM";
        }

        JsonNode fieldsNode = node == null ? null : node.get("fields");
        if (fieldsNode == null && summary != null) {
            fieldsNode = summary.get("fields");
        }
        List<Confirmation.Field> fields = support.fields(fieldsNode);

        Instant expiresAt = HttpAgentSupport.instant(node, "expiresAt");
        if (expiresAt == null) {
            expiresAt = HttpAgentSupport.instant(body, "confirmationExpiresAt");
        }
        String status = firstText(node, "status");
        return new Confirmation(token, title, risk, fields, expiresAt, status == null ? "PENDING" : status);
    }

    protected AgentError errorFrom(HttpAgentSupport.HttpResult result, JsonNode body) {
        JsonNode errorNode = body == null ? null : body.get("error");
        AgentError error = parseError(errorNode, result.status(), body);
        if (error != null) {
            return error;
        }
        String code = HttpAgentSupport.text(body, "code", "errorCode");
        String message = HttpAgentSupport.text(body, "message", "errorMessage");
        if (code == null) {
            code = "HTTP_" + result.status();
        }
        if (message == null) {
            message = result.raw() == null || result.raw().isBlank() ? "子 Agent 调用失败" : result.raw();
        }
        return mapError(code, message, result.status());
    }

    protected AgentError parseError(JsonNode errorNode, int httpStatus, JsonNode body) {
        if (errorNode != null && errorNode.isObject()) {
            String code = HttpAgentSupport.text(errorNode, "code");
            return new AgentError(
                    code == null ? "HTTP_" + httpStatus : code,
                    defaultText(HttpAgentSupport.text(errorNode, "category"), category(codeOf(code))),
                    HttpAgentSupport.bool(errorNode, "retryable", retryable(codeOf(code), httpStatus)),
                    HttpAgentSupport.text(errorNode, "message"));
        }
        if (httpStatus >= 400) {
            String code = HttpAgentSupport.text(body, "code", "errorCode");
            String message = HttpAgentSupport.text(body, "message", "errorMessage");
            return mapError(code == null ? "HTTP_" + httpStatus : code,
                    message == null ? "子 Agent 返回 HTTP " + httpStatus : message, httpStatus);
        }
        if ("FAILED".equalsIgnoreCase(HttpAgentSupport.text(body, "status"))) {
            String code = HttpAgentSupport.text(body, "errorCode", "code");
            String message = HttpAgentSupport.text(body, "errorMessage", "message");
            if (code != null || message != null) {
                return mapError(code == null ? "INTERNAL_ERROR" : code,
                        message == null ? "任务失败" : message, httpStatus);
            }
        }
        return null;
    }

    protected TaskStatus normalizeStatus(String raw, AgentError error) {
        if (raw == null) {
            return error == null ? TaskStatus.PENDING : TaskStatus.FAILED;
        }
        String status = raw.toUpperCase();
        return switch (status) {
            case "SUCCEEDED", "SUCCESS", "COMPLETED" -> TaskStatus.SUCCEEDED;
            case "REJECTED" -> TaskStatus.REJECTED;
            case "CANCELLED", "CANCELED" -> TaskStatus.CANCELLED;
            case "WAITING_CONFIRM", "CONFIRM_REQUIRED" -> TaskStatus.WAITING_CONFIRM;
            case "RUNNING", "EXECUTING", "PROCESSING" -> TaskStatus.RUNNING;
            case "PENDING", "QUEUED", "ACCEPTED", "CREATED" -> TaskStatus.PENDING;
            case "FAILED", "ERROR" -> isUserRejected(error) ? TaskStatus.REJECTED : TaskStatus.FAILED;
            default -> error == null ? TaskStatus.PENDING : TaskStatus.FAILED;
        };
    }

    protected AgentError mapError(String code, String message, int httpStatus) {
        String normalized = code == null ? "" : code.toUpperCase();
        if (httpStatus == 401 || httpStatus == 403) {
            return new AgentError(normalized.isBlank() ? "AUTH_FORBIDDEN" : normalized,
                    "AUTH", false, message);
        }
        if (httpStatus == 429) {
            return new AgentError(normalized.isBlank() ? "RATE_LIMITED" : normalized,
                    "RATE_LIMIT", true, message);
        }
        if (httpStatus == 503) {
            return new AgentError(normalized.isBlank() ? "DOWNSTREAM_UNAVAILABLE" : normalized,
                    "DEPENDENCY", true, message);
        }
        if (dialect == AgentDialect.PROPERTY) {
            return propertyError(normalized, message);
        }
        return contractError(normalized, message);
    }

    private AgentError propertyError(String code, String message) {
        return switch (code) {
            case "10001", "10003" -> new AgentError(code, "AUTH", false, message);
            case "20001" -> new AgentError(code, "NOT_FOUND", false, message);
            case "20002" -> new AgentError(code, "CONFLICT", false, message);
            case "20005" -> new AgentError(code, "TIMEOUT", false, message);
            case "50004" -> new AgentError(code, "DEPENDENCY", true, message);
            case "60001" -> new AgentError(code, "VALIDATION", false, message);
            default -> new AgentError(code, "INTERNAL", false, message);
        };
    }

    private AgentError contractError(String code, String message) {
        if (code.startsWith("AUTH_")) {
            return new AgentError(code, "AUTH", false, message);
        }
        return switch (code) {
            case "TASK_STATE_CONFLICT", "CONFIRM_REQUIRED", "IDEMPOTENCY_CONFLICT", "CONFIRM_TOKEN_INVALID" ->
                    new AgentError(code, "CONFLICT", false, message);
            case "TASK_NOT_FOUND", "CAPABILITY_NOT_FOUND", "SESSION_NOT_FOUND" ->
                    new AgentError(code, "NOT_FOUND", false, message);
            case "RATE_LIMITED", "QUEUE_FULL" -> new AgentError(code, "RATE_LIMIT", true, message);
            case "DOWNSTREAM_UNAVAILABLE" -> new AgentError(code, "DEPENDENCY", true, message);
            case "CONFIRM_REJECTED" -> new AgentError(code, "USER_REJECTED", false, message);
            case "INPUT_INVALID", "CAPABILITY_DISABLED", "VALIDATION_ERROR" ->
                    new AgentError(code, "VALIDATION", false, message);
            default -> new AgentError(code, "INTERNAL", false, message);
        };
    }

    private AdapterResult failure(String childTaskId, AgentError error) {
        return new AdapterResult(childTaskId, TaskStatus.FAILED, null, error, null,
                null, "FAILED", true, List.of());
    }

    private static boolean isUserRejected(AgentError error) {
        return error != null && ("USER_REJECTED".equals(error.category())
                || "CONFIRM_REJECTED".equals(error.code()));
    }

    private static String codeOf(String code) {
        return code == null ? "" : code;
    }

    private static String category(String code) {
        if (code.startsWith("AUTH_")) {
            return "AUTH";
        }
        return switch (code) {
            case "RATE_LIMITED", "QUEUE_FULL" -> "RATE_LIMIT";
            case "DOWNSTREAM_UNAVAILABLE" -> "DEPENDENCY";
            case "TASK_STATE_CONFLICT", "CONFIRM_REQUIRED" -> "CONFLICT";
            case "TASK_NOT_FOUND", "CAPABILITY_NOT_FOUND" -> "NOT_FOUND";
            default -> "INTERNAL";
        };
    }

    private static boolean retryable(String code, int httpStatus) {
        return httpStatus == 429 || httpStatus == 503
                || "RATE_LIMITED".equals(code) || "QUEUE_FULL".equals(code)
                || "DOWNSTREAM_UNAVAILABLE".equals(code);
    }

    private static JsonNode first(JsonNode first, JsonNode second) {
        return first != null && !first.isNull() ? first : second;
    }

    private static String firstText(JsonNode node, String... names) {
        return node == null ? null : HttpAgentSupport.text(node, names);
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String path(String configured, String fallback) {
        return configured == null || configured.isBlank() ? fallback : configured;
    }
}
