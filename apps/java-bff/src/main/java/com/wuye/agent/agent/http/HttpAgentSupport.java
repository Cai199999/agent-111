package com.wuye.agent.agent.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 真实子 Agent 的 HTTP 基础能力：请求头、委托令牌缓存、JSON 解析、方言解包。
 *
 * <p>只用 JDK HttpClient，避免引入额外客户端；所有调用都在 Java 侧，
 * Python 与浏览器不接触子 Agent 凭据。</p>
 */
@Component
public class HttpAgentSupport {

    private static final Logger log = LoggerFactory.getLogger(HttpAgentSupport.class);

    private final AgentHttpProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final Map<String, CachedToken> delegationCache = new ConcurrentHashMap<>();

    public HttpAgentSupport(AgentHttpProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .build();
    }

    public HttpResult get(AgentHttpProperties.Endpoint endpoint, String path,
                          String actorId, String traceId, boolean delegation) {
        return send("GET", endpoint, path, null, actorId, traceId, delegation);
    }

    public HttpResult post(AgentHttpProperties.Endpoint endpoint, String path, Object body,
                           String actorId, String traceId, boolean delegation) {
        return send("POST", endpoint, path, body, actorId, traceId, delegation);
    }

    public HttpResult send(String method, AgentHttpProperties.Endpoint endpoint, String path, Object body,
                           String actorId, String traceId, boolean withDelegation) {
        try {
            URI uri = URI.create(stripTrailingSlash(endpoint.getBaseUrl()) + path);
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(properties.getReadTimeoutMs()))
                    .header("Accept", "application/json");

            if (!"GET".equalsIgnoreCase(method)) {
                builder.header("Content-Type", "application/json");
            }
            if (notBlank(endpoint.getInternalToken())) {
                builder.header("X-Internal-Token", endpoint.getInternalToken());
            }
            if (notBlank(actorId)) {
                builder.header("X-Actor-Id", actorId);
            }
            if (notBlank(endpoint.getRoles())) {
                builder.header("X-Actor-Roles", endpoint.getRoles());
            }
            if (notBlank(endpoint.getTenantId())) {
                builder.header("X-Tenant-Id", endpoint.getTenantId());
            }
            if (notBlank(traceId)) {
                builder.header("X-Trace-Id", traceId);
                builder.header("X-Correlation-Id", traceId);
            }
            if (withDelegation && endpoint.isUseDelegation()) {
                String delegation = delegationToken(endpoint, actorId, traceId);
                if (delegation != null) {
                    builder.header("X-Delegation-Token", delegation);
                }
            }

            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.method(method, HttpRequest.BodyPublishers.ofString(
                        mapper.writeValueAsString(body), StandardCharsets.UTF_8));
            }

            HttpResponse<String> response = http.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new HttpResult(response.statusCode(), response.body(), json(response.body()));
        } catch (Exception ex) {
            String reason = ex.getMessage() == null || ex.getMessage().isBlank()
                    ? ex.getClass().getSimpleName() : ex.getMessage();
            throw new AgentHttpException("调用子 Agent 失败：" + reason, ex);
        }
    }

    /**
     * 委托令牌由子 Agent 签发；失败/未实现时返回 null，调用方回退到内部令牌 + Actor 头。
     */
    public String delegationToken(AgentHttpProperties.Endpoint endpoint, String actorId, String traceId) {
        if (!endpoint.isUseDelegation()) {
            return null;
        }
        String key = endpoint.getAudience() + "|" + actorId + "|" + endpoint.getDelegationScopes();
        CachedToken cached = delegationCache.get(key);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return cached.token();
        }

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("actorId", numericOrString(actorId));
        request.put("roles", split(endpoint.getRoles()));
        request.put("scopes", split(endpoint.getDelegationScopes()));
        request.put("ttlSeconds", 600);
        request.put("audience", endpoint.getAudience());
        request.put("tenantId", endpoint.getTenantId());
        try {
            HttpResult result = send("POST", endpoint, endpoint.getDelegationPath(), request,
                    actorId, traceId, false);
            if (result.status() < 200 || result.status() >= 300) {
                log.debug("委托令牌签发不可用 status={} audience={}", result.status(), endpoint.getAudience());
                return null;
            }
            JsonNode data = result.body().has("data") ? result.body().get("data") : result.body();
            String token = text(data, "token", "delegationToken", "accessToken");
            if (token == null) {
                return null;
            }
            long ttl = data.has("expiresInSeconds") ? data.get("expiresInSeconds").asLong(600) : 600;
            delegationCache.put(key, new CachedToken(token, Instant.now().plusSeconds(Math.max(30, ttl - 60))));
            return token;
        } catch (Exception ex) {
            log.debug("委托令牌签发失败 audience={}：{}", endpoint.getAudience(), ex.getMessage());
            return null;
        }
    }

    public int ping(AgentHttpProperties.Endpoint endpoint, String path, String actorId, String traceId) {
        try {
            return get(endpoint, path, actorId, traceId, false).status();
        } catch (Exception ex) {
            return 0;
        }
    }

    public JsonNode json(String raw) {
        if (raw == null || raw.isBlank()) {
            return NullNode.getInstance();
        }
        try {
            return mapper.readTree(raw);
        } catch (Exception ex) {
            return NullNode.getInstance();
        }
    }

    /**
     * 物业是 {code,data} 包络；合同是裸对象。统一取业务 data。
     */
    public JsonNode unwrap(JsonNode body, AgentDialect dialect) {
        if (body == null || body.isNull()) {
            return NullNode.getInstance();
        }
        if (dialect == AgentDialect.PROPERTY && body.has("data")
                && (body.has("code") || body.has("success"))) {
            return body.get("data");
        }
        return body;
    }

    public Object object(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return mapper.convertValue(node, Object.class);
    }

    public Map<String, Object> map(JsonNode node) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        return mapper.convertValue(node, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
        });
    }

    public List<com.wuye.agent.agent.model.Confirmation.Field> fields(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<com.wuye.agent.agent.model.Confirmation.Field> fields = new ArrayList<>();
        for (JsonNode item : node) {
            fields.add(new com.wuye.agent.agent.model.Confirmation.Field(
                    text(item, "label", "name", "key"),
                    text(item, "value", "text")));
        }
        return fields;
    }

    public static String text(JsonNode node, String... names) {
        if (node == null || node.isNull()) {
            return null;
        }
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && !value.isNull()) {
                return value.asText();
            }
        }
        return null;
    }

    public static boolean bool(JsonNode node, String name, boolean fallback) {
        if (node == null || !node.has(name) || node.get(name).isNull()) {
            return fallback;
        }
        return node.get(name).asBoolean(fallback);
    }

    public static Integer integer(JsonNode node, String... names) {
        for (String name : names) {
            if (node != null && node.has(name) && !node.get(name).isNull()) {
                return node.get(name).asInt();
            }
        }
        return null;
    }

    public static Instant instant(JsonNode node, String name) {
        if (node == null || !node.has(name) || node.get(name).isNull()) {
            return null;
        }
        String value = node.get(name).asText();
        try {
            return Instant.parse(value);
        } catch (Exception ex) {
            try {
                return Instant.ofEpochMilli(Long.parseLong(value));
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private static Object numericOrString(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return value;
        }
    }

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String part : value.split(",")) {
            if (!part.isBlank()) {
                result.add(part.strip());
            }
        }
        return result;
    }

    private static String stripTrailingSlash(String value) {
        return value != null && value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    public record HttpResult(int status, String raw, JsonNode body) {
    }

    private record CachedToken(String token, Instant expiresAt) {
    }

    public static class AgentHttpException extends RuntimeException {
        public AgentHttpException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
