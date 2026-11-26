package com.wuye.agent.orchestration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuye.agent.agent.model.Capability;
import com.wuye.agent.routing.AiProperties;
import com.wuye.agent.routing.RouteDecision;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Python 编排图的 HTTP 网关。
 *
 * <p>失败/超时/404 一律返回 empty，由 {@link OrchestrationService} 回退现有同步流程；
 * 编排是增强能力，不能成为用户消息的单点故障。</p>
 */
@Component
public class HttpOrchestrationGateway implements OrchestrationGateway {

    private static final Logger log = LoggerFactory.getLogger(HttpOrchestrationGateway.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final AiProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient http;
    /** 编排接口失败后 30 秒内直接跳过，避免每条消息都等超时。 */
    private volatile long nextRetryAt = 0;

    public HttpOrchestrationGateway(AiProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(Math.min(properties.getTimeoutMs(), 3000)))
                .build();
    }

    @Override
    public Optional<OrchestrationResult> plan(PlanRequest request) {
        if (!available() || System.currentTimeMillis() < nextRetryAt) {
            return Optional.empty();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("traceId", request.traceId());
        body.put("sessionNo", request.sessionNo());
        body.put("userMessage", request.userMessage());
        body.put("actorId", request.actorId());
        body.put("tenantId", request.tenantId());
        body.put("capabilities", capabilityPayload(request.capabilities()));
        body.put("plan", planPayload(request.plan()));
        body.put("dispatchMode", "PLAN_ONLY");
        return post("/internal/v1/ai/orchestrate", body);
    }

    @Override
    public Optional<OrchestrationResult> verify(VerifyRequest request) {
        if (!available() || System.currentTimeMillis() < nextRetryAt) {
            return Optional.empty();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("traceId", request.traceId());
        body.put("sessionNo", request.sessionNo());
        body.put("plan", planPayload(request.plan()));
        body.put("steps", stepPayload(request.steps()));
        return post("/internal/v1/ai/orchestrate/verify", body);
    }

    private Optional<OrchestrationResult> post(String path, Map<String, Object> body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(
                            URI.create(stripTrailingSlash(properties.getBaseUrl()) + path))
                    // 编排是同步路径上的增强，超时必须短于 LLM 路由。
                    .timeout(Duration.ofMillis(Math.max(500, Math.min(properties.getTimeoutMs(), 3000))))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(body), StandardCharsets.UTF_8));
            if (!blank(properties.getInternalToken())) {
                builder.header("X-Internal-Token", properties.getInternalToken());
            }
            HttpResponse<String> response = http.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                nextRetryAt = System.currentTimeMillis() + 30_000;
                log.warn("编排接口不可用 path={} status={} body={}",
                        path, response.statusCode(), truncate(response.body()));
                return Optional.empty();
            }
            JsonNode data = mapper.readTree(response.body());
            if (data.has("data")) {
                data = data.get("data");
            }
            nextRetryAt = 0;
            OrchestrationResult result = parse(data);
            log.info("编排结果 path={} verdict={} plan={} steps={}",
                    path, result.verdict(), result.plan().size(), result.steps().size());
            return Optional.of(result);
        } catch (Exception ex) {
            nextRetryAt = System.currentTimeMillis() + 30_000;
            log.warn("编排调用失败 path={} error={}", path, ex.getMessage());
            return Optional.empty();
        }
    }

    private boolean available() {
        return properties.isEnabled()
                && properties.isOrchestrationEnabled()
                && !blank(properties.getBaseUrl());
    }

    private List<Map<String, Object>> capabilityPayload(List<Capability> capabilities) {
        List<Map<String, Object>> payload = new ArrayList<>();
        if (capabilities == null) {
            return payload;
        }
        for (Capability capability : capabilities) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("capabilityId", capability.capabilityId());
            item.put("agentKey", capability.agentKey());
            item.put("displayName", capability.displayName());
            item.put("description", capability.description());
            item.put("readOnly", capability.readOnly());
            item.put("requiresConfirmation", capability.requiresConfirmation());
            payload.add(item);
        }
        return payload;
    }

    private List<Map<String, Object>> planPayload(List<RouteDecision.PlanStep> steps) {
        List<Map<String, Object>> payload = new ArrayList<>();
        if (steps == null) {
            return payload;
        }
        for (RouteDecision.PlanStep step : steps) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("capabilityId", step.capabilityId());
            item.put("args", step.args() == null ? Map.of() : step.args());
            payload.add(item);
        }
        return payload;
    }

    private List<Map<String, Object>> stepPayload(List<OrchestrationResult.StepResult> steps) {
        List<Map<String, Object>> payload = new ArrayList<>();
        if (steps == null) {
            return payload;
        }
        for (OrchestrationResult.StepResult step : steps) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("capabilityId", step.capabilityId());
            item.put("agentKey", step.agentKey());
            item.put("status", step.status());
            item.put("output", step.output());
            item.put("error", step.error());
            item.put("missingFields", step.missingFields());
            payload.add(item);
        }
        return payload;
    }

    private OrchestrationResult parse(JsonNode data) {
        List<RouteDecision.PlanStep> plan = new ArrayList<>();
        JsonNode planNode = data.get("plan");
        if (planNode != null && planNode.isArray()) {
            for (JsonNode item : planNode) {
                String capabilityId = text(item, "capabilityId");
                if (capabilityId == null || capabilityId.isBlank()) {
                    continue;
                }
                Map<String, Object> args = Map.of();
                JsonNode argsNode = item.get("args");
                if (argsNode != null && argsNode.isObject()) {
                    args = mapper.convertValue(argsNode, MAP_TYPE);
                }
                plan.add(new RouteDecision.PlanStep(capabilityId, args));
            }
        }

        List<OrchestrationResult.StepResult> steps = new ArrayList<>();
        JsonNode stepsNode = data.get("steps");
        if (stepsNode != null && stepsNode.isArray()) {
            for (JsonNode item : stepsNode) {
                String capabilityId = text(item, "capabilityId");
                if (capabilityId == null || capabilityId.isBlank()) {
                    continue;
                }
                JsonNode outputNode = item.get("output");
                Object output = outputNode == null || outputNode.isNull()
                        ? null
                        : mapper.convertValue(outputNode, Object.class);
                steps.add(new OrchestrationResult.StepResult(
                        capabilityId,
                        text(item, "agentKey"),
                        text(item, "status") == null ? "PENDING" : text(item, "status"),
                        output,
                        text(item, "error"),
                        stringList(item.get("missingFields"))));
            }
        }

        String verdict = text(data, "verdict");
        return new OrchestrationResult(
                verdict == null ? "FAILED" : verdict,
                text(data, "clarification"),
                plan,
                steps,
                stringList(data.get("notes")));
    }

    private List<String> stringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                values.add(item.asText());
            }
        }
        return values;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String stripTrailingSlash(String value) {
        return value != null && value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 200 ? value : value.substring(0, 200);
    }
}
