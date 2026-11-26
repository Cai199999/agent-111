package com.wuye.agent.agent.install;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuye.agent.agent.adapter.AdapterRegistry;
import com.wuye.agent.agent.adapter.AgentAdapter;
import com.wuye.agent.agent.adapter.DynamicInspectionAgent;
import com.wuye.agent.agent.http.AgentHttpProperties;
import com.wuye.agent.agent.http.DynamicHttpAgentAdapter;
import com.wuye.agent.agent.http.HttpAgentSupport;
import com.wuye.agent.agent.model.AgentDescriptor;
import com.wuye.agent.agent.model.AgentHealth;
import com.wuye.agent.agent.model.Capability;
import com.wuye.agent.auth.DynamicPermissionService;
import com.wuye.agent.common.ApiException;
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 受控的运行时 Agent 安装。
 *
 * <p>支持两种方式：
 * <ol>
 *   <li>安装内置“完整示例”巡检报告 Agent（离线可用，演示完整生命周期）；</li>
 *   <li>从外部 Agent Card URL 安装 Canonical v1 HTTP Agent（只注册契约，不执行任意代码）。</li>
 * </ol>
 *
 * <p>安装成功后写入 {@code wa_agent_registry}（H1），重启由
 * {@link AgentRegistryBootstrap} 恢复；模板 Agent 按 template_key 恢复。
 * 生产化仍应加安装审批与显式 SSRF allowlist。</p>
 */
@Service
public class AgentInstallService {

    private static final Logger log = LoggerFactory.getLogger(AgentInstallService.class);
    private static final String INSPECTION_TEMPLATE = "inspection";

    private final AdapterRegistry registry;
    private final DynamicAgentRegistry dynamicRegistry;
    private final HttpAgentSupport support;
    private final AgentHttpProperties properties;
    private final ObjectMapper mapper;
    private final AgentRegistryStore registryStore;
    private final DynamicPermissionService permissionService;
    private final HttpClient http;

    public AgentInstallService(AdapterRegistry registry, DynamicAgentRegistry dynamicRegistry,
                               HttpAgentSupport support, AgentHttpProperties properties,
                               ObjectMapper mapper, AgentRegistryStore registryStore,
                               DynamicPermissionService permissionService) {
        this.registry = registry;
        this.dynamicRegistry = dynamicRegistry;
        this.support = support;
        this.properties = properties;
        this.mapper = mapper;
        this.registryStore = registryStore;
        this.permissionService = permissionService;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    public Map<String, Object> installTemplate(String template) {
        return installTemplate(template, "system");
    }

    public Map<String, Object> installTemplate(String template, String installedBy) {
        return installTemplateInternal(template, installedBy, true);
    }

    /** 启动恢复用：只注册，不重复写注册表。 */
    Map<String, Object> restoreTemplate(String template) {
        return installTemplateInternal(template, "restore", false);
    }

    private Map<String, Object> installTemplateInternal(String template, String installedBy,
                                                        boolean persist) {
        if (template == null || template.isBlank()
                || !INSPECTION_TEMPLATE.equalsIgnoreCase(template)) {
            throw ApiException.badRequest("TEMPLATE_UNKNOWN", "未知安装模板：" + template);
        }
        if (registry.get(DynamicInspectionAgent.AGENT_KEY) != null) {
            throw ApiException.conflict("AGENT_ALREADY_INSTALLED", "巡检报告 Agent 已安装");
        }
        DynamicInspectionAgent adapter = new DynamicInspectionAgent();
        register(adapter);
        if (persist) {
            try {
                registryStore.save(templateEntry(adapter.descriptor(), template, installedBy));
            } catch (RuntimeException ex) {
                rollback(adapter);
                throw ex;
            }
        }
        log.info("installed template agent {} persist={}", adapter.descriptor().agentKey(), persist);
        return view(adapter.descriptor(), adapter.health());
    }

    public Map<String, Object> installFromCardUrl(String cardUrl, String internalToken) {
        return installFromCardUrl(cardUrl, internalToken, "system");
    }

    public Map<String, Object> installFromCardUrl(String cardUrl, String internalToken,
                                                  String installedBy) {
        if (cardUrl == null || cardUrl.isBlank()) {
            throw ApiException.badRequest("CARD_URL_REQUIRED", "请提供 Agent Card URL");
        }
        URI cardUri = cardUri(cardUrl);
        JsonNode card = fetchCard(cardUri);
        String agentKey = text(card, "agentId", "id");
        if (agentKey == null || agentKey.isBlank()) {
            throw ApiException.badRequest("CARD_INVALID", "Agent Card 缺少 agentId");
        }
        if (registry.get(agentKey) != null) {
            throw ApiException.conflict("AGENT_ALREADY_INSTALLED", "Agent 已存在：" + agentKey);
        }

        List<Capability> capabilities = capabilities(card, agentKey);
        if (capabilities.isEmpty()) {
            throw ApiException.badRequest("CARD_INVALID", "Agent Card 没有可用能力");
        }
        AgentDescriptor descriptor = new AgentDescriptor(
                agentKey,
                text(card, "name", "displayName") == null ? agentKey : text(card, "name", "displayName"),
                "canonical-v1",
                text(card, "version") == null ? "0.1.0" : text(card, "version"),
                "raw",
                endpointPath(card, "health", "/health"),
                endpointPath(card, "ready", "/ready"),
                bool(card.path("features"), "cancel", true),
                text(card.path("features"), "asyncDurability") == null
                        ? "unknown" : text(card.path("features"), "asyncDurability"),
                capabilities);

        AgentHttpProperties.Endpoint endpoint = new AgentHttpProperties.Endpoint();
        endpoint.setBaseUrl(baseUrl(cardUri));
        endpoint.setInternalToken(internalToken == null ? "" : internalToken);
        endpoint.setAudience(agentKey);
        endpoint.setActorId("10001");
        endpoint.setRoles("ADMIN");
        endpoint.setTenantId("0");
        endpoint.setManifestPath(endpointPath(card, "manifest", "/agent/v1/manifest"));
        endpoint.setUseDelegation(false);

        DynamicHttpAgentAdapter adapter = new DynamicHttpAgentAdapter(support, endpoint, descriptor);
        AgentHealth health = adapter.health();
        if (!"UP".equalsIgnoreCase(health.health())) {
            throw ApiException.badRequest("AGENT_UNHEALTHY",
                    "Agent 健康检查失败：" + health.health() + " / " + health.ready());
        }
        register(adapter);
        try {
            registryStore.save(httpEntry(descriptor, endpoint, installedBy));
            permissionService.grant(agentKey, card.path("permissions"), installedBy);
        } catch (RuntimeException ex) {
            rollback(adapter);
            try {
                registryStore.remove(agentKey);
            } catch (RuntimeException cleanup) {
                log.error("安装失败后清理注册记录失败 agent={} error={}",
                        agentKey, cleanup.getMessage());
            }
            throw ex;
        }
        log.info("installed http agent {} from {} persist=true", agentKey, cardUri);
        return view(descriptor, health);
    }

    public Map<String, Object> uninstall(String agentKey) {
        if (!dynamicRegistry.isDynamic(agentKey)) {
            throw ApiException.badRequest("AGENT_NOT_DYNAMIC", "只能卸载运行时安装的 Agent");
        }
        AgentAdapter removed = registry.unregister(agentKey);
        if (removed == null) {
            throw ApiException.notFound("AGENT_NOT_FOUND", "Agent 不存在：" + agentKey);
        }
        dynamicRegistry.markRemoved(agentKey,
                removed.descriptor().capabilities().stream().map(Capability::capabilityId).toList());
        try {
            registryStore.remove(agentKey);
        } catch (RuntimeException ex) {
            // 内存已移除但注册表未标记成功：记录错误，重启后可能被恢复，需人工处理。
            log.error("移除 Agent 注册记录失败 agent={} error={}", agentKey, ex.getMessage());
        }
        try {
            permissionService.revoke(agentKey);
        } catch (RuntimeException ex) {
            log.error("撤销动态权限失败 agent={} error={}", agentKey, ex.getMessage());
        }
        if (removed instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ex) {
                log.warn("close dynamic agent failed agent={} error={}", agentKey, ex.getMessage());
            }
        }
        log.info("uninstalled dynamic agent {}", agentKey);
        return Map.of("agentKey", agentKey, "uninstalled", true);
    }

    private void register(AgentAdapter adapter) {
        registry.register(adapter);
        dynamicRegistry.markInstalled(adapter.descriptor().agentKey(),
                adapter.descriptor().capabilities().stream().map(Capability::capabilityId).toList());
    }

    /** 写注册表失败时回滚内存注册，避免“内存有、库里没有”。 */
    private void rollback(AgentAdapter adapter) {
        String agentKey = adapter.descriptor().agentKey();
        registry.unregister(agentKey);
        dynamicRegistry.markRemoved(agentKey,
                adapter.descriptor().capabilities().stream().map(Capability::capabilityId).toList());
    }

    private AgentRegistryEntry httpEntry(AgentDescriptor descriptor,
                                         AgentHttpProperties.Endpoint endpoint,
                                         String installedBy) {
        Instant now = Instant.now();
        return new AgentRegistryEntry(
                descriptor.agentKey(),
                descriptor.displayName(),
                descriptor.contractVersion(),
                descriptor.dialect(),
                AgentRegistryEntry.KIND_HTTP,
                null,
                descriptorJson(descriptor),
                endpoint.getBaseUrl(),
                endpoint.getInternalToken(),
                endpoint.getAudience(),
                endpoint.getActorId(),
                endpoint.getRoles(),
                endpoint.getTenantId(),
                endpoint.isUseDelegation(),
                descriptor.healthPath(),
                descriptor.readyPath(),
                endpoint.getManifestPath(),
                AgentRegistryEntry.STATUS_ACTIVE,
                installedBy,
                now,
                now);
    }

    private AgentRegistryEntry templateEntry(AgentDescriptor descriptor, String template,
                                             String installedBy) {
        Instant now = Instant.now();
        return new AgentRegistryEntry(
                descriptor.agentKey(),
                descriptor.displayName(),
                descriptor.contractVersion(),
                descriptor.dialect(),
                AgentRegistryEntry.KIND_TEMPLATE,
                template,
                descriptorJson(descriptor),
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                descriptor.healthPath(),
                descriptor.readyPath(),
                null,
                AgentRegistryEntry.STATUS_ACTIVE,
                installedBy,
                now,
                now);
    }

    private String descriptorJson(AgentDescriptor descriptor) {
        try {
            return mapper.writeValueAsString(descriptor);
        } catch (Exception ex) {
            throw new IllegalStateException("序列化 Agent 描述失败：" + descriptor.agentKey(), ex);
        }
    }

    private JsonNode fetchCard(URI cardUri) {
        try {
            HttpRequest request = HttpRequest.newBuilder(cardUri)
                    .timeout(Duration.ofSeconds(3))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw ApiException.badRequest("CARD_FETCH_FAILED",
                        "拉取 Agent Card 失败 HTTP " + response.statusCode());
            }
            JsonNode body = mapper.readTree(response.body());
            if (body.has("data") && body.has("code")) {
                return body.get("data");
            }
            return body;
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw ApiException.badRequest("CARD_FETCH_FAILED",
                    "拉取 Agent Card 失败：" + ex.getMessage());
        }
    }

    private List<Capability> capabilities(JsonNode card, String agentKey) {
        JsonNode node = card.get("capabilities");
        List<Capability> result = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return result;
        }
        for (JsonNode item : node) {
            String id;
            String name;
            String description;
            boolean readOnly;
            boolean confirm;
            List<String> modes;
            boolean enabled;
            if (item.isTextual()) {
                id = item.asText();
                name = id;
                description = id;
                readOnly = false;
                confirm = true;
                modes = List.of("SYNC");
                enabled = true;
            } else {
                id = text(item, "capabilityId", "id", "name");
                name = text(item, "name", "displayName");
                description = text(item, "description");
                readOnly = bool(item, "readOnly", false);
                // 0.11.0：WRITE 不等于必须确认，以显式 requiresConfirmation 为准；
                // 缺省不推断，实际确认仍由子 Agent 的 WAITING_CONFIRM 决定。
                confirm = bool(item, "requiresConfirmation", false);
                modes = stringList(item.get("executionModes"), List.of("SYNC"));
                enabled = bool(item, "enabled", true);
            }
            if (id == null || id.isBlank()) {
                continue;
            }
            result.add(new Capability(
                    id,
                    agentKey,
                    text(item, "taskType") == null ? lastSegment(id) : text(item, "taskType"),
                    namespace(id),
                    name == null ? id : name,
                    description == null ? name : description,
                    readOnly,
                    confirm,
                    modes,
                    enabled));
        }
        return result;
    }

    private Map<String, Object> view(AgentDescriptor descriptor, AgentHealth health) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agentKey", descriptor.agentKey());
        result.put("displayName", descriptor.displayName());
        result.put("version", descriptor.contractVersion());
        result.put("dialect", descriptor.dialect());
        result.put("capabilities", descriptor.capabilities().stream()
                .map(capability -> Map.of(
                        "capabilityId", capability.capabilityId(),
                        "name", capability.displayName(),
                        "readOnly", capability.readOnly(),
                        "requiresConfirmation", capability.requiresConfirmation(),
                        "executionModes", capability.executionModes()))
                .toList());
        result.put("health", Map.of(
                "status", health.health(),
                "ready", health.ready(),
                "latencyMs", health.latencyMs()));
        result.put("dynamic", true);
        return result;
    }

    private URI cardUri(String raw) {
        try {
            URI uri = URI.create(raw.strip());
            String scheme = uri.getScheme();
            if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
                    || uri.getHost() == null) {
                throw ApiException.badRequest("CARD_URL_INVALID", "只支持 http/https Agent Card URL");
            }
            // 基础 SSRF 防护：禁止云元数据地址；生产化应改为显式 allowlist。
            String host = uri.getHost();
            if ("169.254.169.254".equals(host) || "metadata.google.internal".equalsIgnoreCase(host)) {
                throw ApiException.badRequest("CARD_URL_BLOCKED", "禁止访问云元数据地址");
            }
            String path = uri.getPath() == null ? "" : uri.getPath();
            // 支持三种输入：基础 URL、完整 Agent Card URL、Manifest URL。
            if (path.endsWith(".json") || path.endsWith("/agent/v1/manifest")) {
                return uri;
            }
            String prefix = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
            return URI.create(scheme + "://" + uri.getRawAuthority()
                    + prefix + "/.well-known/agent-card.json");
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw ApiException.badRequest("CARD_URL_INVALID", "Agent Card URL 不合法");
        }
    }

    private String baseUrl(URI cardUri) {
        String path = cardUri.getPath() == null ? "" : cardUri.getPath();
        String prefix = path;
        for (String suffix : List.of("/.well-known/agent-card.json", "/agent/v1/manifest")) {
            if (prefix.endsWith(suffix)) {
                prefix = prefix.substring(0, prefix.length() - suffix.length());
                break;
            }
        }
        if (prefix.equals(path) && path.lastIndexOf('/') > 0) {
            prefix = path.substring(0, path.lastIndexOf('/'));
        }
        if ("/".equals(prefix)) {
            prefix = "";
        }
        return cardUri.getScheme() + "://" + cardUri.getAuthority() + prefix;
    }

    private String endpointPath(JsonNode card, String name, String fallback) {
        JsonNode endpoints = card.get("endpoints");
        if (endpoints != null && endpoints.has(name)) {
            String value = endpoints.get(name).asText();
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return fallback;
    }

    private static String text(JsonNode node, String... names) {
        if (node == null || node.isNull()) {
            return null;
        }
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && !value.isNull()) {
                String text = value.asText();
                if (text != null && !text.isBlank()) {
                    return text;
                }
            }
        }
        return null;
    }

    private static boolean bool(JsonNode node, String name, boolean fallback) {
        if (node == null || !node.has(name) || node.get(name).isNull()) {
            return fallback;
        }
        return node.get(name).asBoolean(fallback);
    }

    private static List<String> stringList(JsonNode node, List<String> fallback) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return fallback;
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.asText().isBlank()) {
                result.add(item.asText());
            }
        }
        return result.isEmpty() ? fallback : result;
    }

    private static String namespace(String capabilityId) {
        int index = capabilityId.indexOf('.');
        return index > 0 ? capabilityId.substring(0, index) : "dynamic";
    }

    private static String lastSegment(String capabilityId) {
        int index = capabilityId.lastIndexOf('.');
        return index >= 0 && index + 1 < capabilityId.length()
                ? capabilityId.substring(index + 1) : capabilityId;
    }
}
