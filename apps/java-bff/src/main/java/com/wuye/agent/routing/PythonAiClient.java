package com.wuye.agent.routing;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuye.agent.agent.adapter.AdapterRegistry;
import com.wuye.agent.agent.catalog.CapabilitySchemaService;
import com.wuye.agent.agent.model.Capability;
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
 * Java → Python AI 的路由客户端。
 *
 * <p>Python 只拿能力目录和用户消息，不接触子 Agent 凭据；失败/超时返回 empty，
 * HybridRouter 自动回退规则。LLM 输出仍由 Java 校验能力白名单。</p>
 */
@Component
public class PythonAiClient implements LlmRouterClient {

    private static final Logger log = LoggerFactory.getLogger(PythonAiClient.class);

    private final AiProperties properties;
    private final AdapterRegistry registry;
    private final CapabilitySchemaService schemaService;
    private final ObjectMapper mapper;
    private final HttpClient http;
    /** 简单熔断：LLM 失败后 30s 内直接回退规则，避免每轮都等超时。 */
    private volatile long nextRetryAt = 0;

    public PythonAiClient(AiProperties properties, AdapterRegistry registry,
                          CapabilitySchemaService schemaService, ObjectMapper mapper) {
        this.properties = properties;
        this.registry = registry;
        this.schemaService = schemaService;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(Math.min(properties.getTimeoutMs(), 3000)))
                .build();
    }

    @Override
    public Optional<RouteDecision> route(String message) {
        if (!properties.isEnabled() || isBlank(properties.getBaseUrl())) {
            return Optional.empty();
        }
        if (System.currentTimeMillis() < nextRetryAt) {
            return Optional.empty();
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("userMessage", message);
            body.put("recentTurns", List.of());
            body.put("capabilities", capabilityPayload());

            HttpRequest.Builder builder = HttpRequest.newBuilder(
                            URI.create(stripTrailingSlash(properties.getBaseUrl()) + "/internal/v1/ai/route"))
                    .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(body), StandardCharsets.UTF_8));
            if (!isBlank(properties.getInternalToken())) {
                builder.header("X-Internal-Token", properties.getInternalToken());
            }

            HttpResponse<String> response = http.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                nextRetryAt = System.currentTimeMillis() + 30_000;
                log.warn("LLM 路由不可用 status={} body={}", response.statusCode(), truncate(response.body()));
                return Optional.empty();
            }
            JsonNode root = mapper.readTree(response.body());
            JsonNode data = root.has("data") ? root.get("data") : root;
            nextRetryAt = 0;
            RouteDecision decision = toDecision(data);
            log.info("LLM 路由 capability={} confidence={} clarify={} usage={}",
                    decision.capabilityId(), decision.confidence(), decision.needClarification(),
                    data.get("usage"));
            return Optional.of(decision);
        } catch (Exception ex) {
            nextRetryAt = System.currentTimeMillis() + 30_000;
            log.warn("LLM 路由失败，回退规则：{}", ex.getMessage());
            return Optional.empty();
        }
    }

    private RouteDecision toDecision(JsonNode data) {
        String capabilityId = text(data, "capabilityId");
        boolean clarify = data.path("needClarification").asBoolean(false);
        double confidence = data.path("confidence").asDouble(0);
        String clarification = text(data, "clarification");
        String reason = text(data, "reason");
        Map<String, Object> args = args(data.get("args"));

        if (capabilityId != null && registry.capability(capabilityId).isEmpty()) {
            capabilityId = null;
            clarify = true;
            clarification = clarification == null ? "这个能力当前不可用，请换一种说法。" : clarification;
        }
        if (confidence < properties.getConfidenceThreshold()) {
            clarify = true;
            if (clarification == null) {
                clarification = "我不太确定你指的是哪类操作，能再说具体一点吗？";
            }
        }

        List<RouteDecision.PlanStep> plan = new ArrayList<>();
        JsonNode planNode = data.get("plan");
        if (planNode != null && planNode.isArray()) {
            for (JsonNode item : planNode) {
                String stepCapability = text(item, "capabilityId");
                if (stepCapability == null || registry.capability(stepCapability).isEmpty()) {
                    continue;
                }
                plan.add(new RouteDecision.PlanStep(stepCapability, args(item.get("args"))));
            }
        }
        List<String> missing = new ArrayList<>();
        JsonNode missingNode = data.get("missingFields");
        if (missingNode != null && missingNode.isArray()) {
            for (JsonNode item : missingNode) {
                if (!item.asText().isBlank()) {
                    missing.add(item.asText());
                }
            }
        }
        if (!missing.isEmpty()) {
            clarify = true;
            if (clarification == null) {
                clarification = "还需要补充：" + String.join("、", missing);
            }
        }
        if (plan.isEmpty() && capabilityId != null) {
            plan.add(new RouteDecision.PlanStep(capabilityId, args));
        }

        if (capabilityId == null) {
            return new RouteDecision(null, args, confidence, true,
                    clarification == null ? "能再说具体一点吗？" : clarification,
                    reason, plan, missing, "LLM");
        }
        return new RouteDecision(capabilityId, args, confidence, clarify,
                clarification, reason, plan, missing, "LLM");
    }

    @Override
    public Optional<RepairOutcome> repair(String userMessage, String capabilityId,
                                          Map<String, Object> args,
                                          String errorCode, String errorMessage) {
        if (!properties.isEnabled() || isBlank(properties.getBaseUrl())
                || System.currentTimeMillis() < nextRetryAt) {
            return Optional.empty();
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("userMessage", userMessage);
            body.put("capabilityId", capabilityId);
            body.put("args", args);
            body.put("errorCode", errorCode);
            body.put("errorMessage", errorMessage);
            body.put("capabilities", capabilityPayload());

            HttpRequest.Builder builder = HttpRequest.newBuilder(
                            URI.create(stripTrailingSlash(properties.getBaseUrl()) + "/internal/v1/ai/repair"))
                    .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(body), StandardCharsets.UTF_8));
            if (!isBlank(properties.getInternalToken())) {
                builder.header("X-Internal-Token", properties.getInternalToken());
            }
            HttpResponse<String> response = http.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                nextRetryAt = System.currentTimeMillis() + 30_000;
                log.warn("LLM 参数修正不可用 status={}", response.statusCode());
                return Optional.empty();
            }
            JsonNode data = mapper.readTree(response.body());
            if (data.has("data")) {
                data = data.get("data");
            }
            nextRetryAt = 0;
            String repairedCapability = text(data, "capabilityId");
            return Optional.of(new RepairOutcome(
                    repairedCapability == null ? capabilityId : repairedCapability,
                    args(data.get("args")),
                    data.path("needClarification").asBoolean(false),
                    text(data, "clarification"),
                    text(data, "reason")));
        } catch (Exception ex) {
            nextRetryAt = System.currentTimeMillis() + 30_000;
            log.warn("LLM 参数修正失败：{}", ex.getMessage());
            return Optional.empty();
        }
    }

    private List<Map<String, Object>> capabilityPayload() {
        List<Map<String, Object>> payload = new ArrayList<>();
        for (Capability capability : registry.capabilities()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("capabilityId", capability.capabilityId());
            item.put("displayName", capability.displayName());
            item.put("description", capability.description());
            item.put("readOnly", capability.readOnly());
            item.put("requiresConfirmation", capability.requiresConfirmation());
            Map<String, Object> schema = schemaService.schemaFor(capability.capabilityId());
            if (schema != null) {
                item.put("inputSchema", schema);
            }
            payload.add(item);
        }
        return payload;
    }

    private Map<String, Object> args(JsonNode node) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        return mapper.convertValue(node, new TypeReference<Map<String, Object>>() {
        });
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node == null ? null : node.get(name);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > 300 ? value.substring(0, 300) + "…" : value;
    }
}
