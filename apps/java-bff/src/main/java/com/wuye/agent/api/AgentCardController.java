package com.wuye.agent.api;

import com.wuye.agent.agent.adapter.AdapterRegistry;
import com.wuye.agent.agent.catalog.AgentCatalogService;
import com.wuye.agent.agent.model.AgentDescriptor;
import com.wuye.agent.agent.model.AgentHealth;
import com.wuye.agent.agent.model.Capability;
import com.wuye.agent.auth.AuthContext;
import com.wuye.agent.auth.AuthUser;
import com.wuye.agent.auth.PolicyService;
import com.wuye.agent.common.ApiException;
import com.wuye.agent.common.ApiResponse;
import com.wuye.agent.common.Ids;
import com.wuye.agent.memory.MainAgentTools;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * A2A 发现层（轻量）。
 *
 * <p>只做 Agent Card 发现，不实现 Google A2A 的 JSON-RPC 传输。
 * 这样既能让外部系统“看见”主 Agent/子 Agent 的能力，又不需要重写内部
 * {@code agent-task-v1} 任务模型；后续加 JSON-RPC facade 时核心不变。</p>
 */
@RestController
public class AgentCardController {

    private final AdapterRegistry registry;
    private final AgentCatalogService catalogService;
    private final PolicyService policyService;
    private final Ids ids;

    public AgentCardController(AdapterRegistry registry, AgentCatalogService catalogService,
                               PolicyService policyService, Ids ids) {
        this.registry = registry;
        this.catalogService = catalogService;
        this.policyService = policyService;
        this.ids = ids;
    }

    /** 标准 A2A 发现入口：公开、只读、脱敏，不含内部地址和令牌。 */
    @GetMapping("/.well-known/agent-card.json")
    public Map<String, Object> mainCard() {
        return mainCardView();
    }

    /** 当前用户可见的子 Agent Card（按权限裁剪）。 */
    @GetMapping("/api/v1/agents/{agentKey}/card")
    public ApiResponse<Map<String, Object>> agentCard(
            @PathVariable String agentKey,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        AuthUser user = AuthContext.require();
        boolean visible = catalogService.forUser(user).stream()
                .anyMatch(agent -> agent.agentKey().equals(agentKey));
        if (!visible) {
            throw ApiException.notFound("AGENT_NOT_FOUND", "Agent 不可见或未注册：" + agentKey);
        }
        AgentDescriptor descriptor = descriptor(agentKey);
        if (descriptor == null) {
            throw ApiException.notFound("AGENT_NOT_FOUND", "Agent 未注册：" + agentKey);
        }
        return ApiResponse.ok(subAgentCard(descriptor), trace(traceId));
    }

    /** 管理端 A2A 能力总览：给演示页/运维看协议、能力、健康。 */
    @GetMapping("/api/v1/admin/a2a/summary")
    public ApiResponse<Map<String, Object>> summary(
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        policyService.requireAdmin(AuthContext.require());
        List<Map<String, Object>> agents = new ArrayList<>();
        for (AgentDescriptor descriptor : registry.descriptors()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("card", subAgentCard(descriptor));
            item.put("health", health(descriptor.agentKey()));
            agents.add(item);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("main", mainCardView());
        summary.put("agents", agents);
        summary.put("compatibility", Map.of(
                "standard", "google-a2a",
                "status", "discovery-only",
                "jsonRpc", false,
                "pushNotifications", false,
                "note", "已提供 Agent Card；JSON-RPC transport 可作为 facade 后补，核心任务模型不变"));
        return ApiResponse.ok(summary, trace(traceId));
    }

    private Map<String, Object> mainCardView() {
        List<Map<String, Object>> capabilities = new ArrayList<>();
        for (Capability capability : registry.capabilities()) {
            capabilities.add(capabilityView(capability));
        }
        for (Map<String, Object> tool : MainAgentTools.payload()) {
            capabilities.add(Map.of(
                    "capabilityId", tool.get("capabilityId"),
                    "name", tool.get("displayName"),
                    "readOnly", tool.get("readOnly"),
                    "requiresConfirmation", tool.get("requiresConfirmation"),
                    "executionModes", tool.get("executionModes")));
        }

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("agentId", "wuye-main-agent");
        card.put("name", "物业主 Agent");
        card.put("description", "单对话框编排主 Agent：路由、补参、确认、审计、人工介入");
        card.put("version", "0.1.0");
        card.put("protocol", "agent-task-v1");
        card.put("protocolVersion", "1.0");
        card.put("a2aCompatibility", "discovery-only");
        card.put("capabilities", capabilities);
        card.put("endpoints", Map.of(
                "agentCard", "/.well-known/agent-card.json",
                "chat", "/api/v1/chat/sessions/{sessionNo}/messages",
                "tasks", "/api/v1/tasks/{taskNo}",
                "confirm", "/api/v1/tasks/{taskNo}/confirm",
                "cancel", "/api/v1/tasks/{taskNo}/cancel",
                "events", "/api/v1/tasks/{taskNo}/events",
                "tools", "/api/v1/tools"));
        card.put("auth", Map.of(
                "schemes", List.of("BearerJWT"),
                "note", "内部 X-Internal-Token / DelegationToken 不下发、不暴露"));
        card.put("features", Map.of(
                "sse", true,
                "humanConfirmation", true,
                "cancel", true,
                "async", true,
                "idempotency", true,
                "jsonRpc", false,
                "pushNotifications", false));
        card.put("subAgents", registry.descriptors().stream()
                .map(descriptor -> Map.of(
                        "agentId", descriptor.agentKey(),
                        "version", descriptor.contractVersion(),
                        "dialect", descriptor.dialect()))
                .toList());
        return card;
    }

    private Map<String, Object> subAgentCard(AgentDescriptor descriptor) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("agentId", descriptor.agentKey());
        card.put("name", descriptor.displayName());
        card.put("version", descriptor.contractVersion());
        card.put("protocol", "agent-task-v1");
        card.put("dialect", descriptor.dialect());
        card.put("envelope", descriptor.envelope());
        card.put("a2aCompatibility", "dialect-adapter");
        card.put("capabilities", descriptor.capabilities().stream()
                .map(this::capabilityView)
                .toList());
        card.put("endpoints", Map.of(
                "health", descriptor.healthPath(),
                "ready", descriptor.readyPath(),
                "tasks", "/agent/v1/tasks",
                "confirm", "/agent/v1/tasks/{taskId}/confirm",
                "cancel", "/agent/v1/tasks/{taskId}/cancel",
                "events", "/agent/v1/tasks/{taskId}/events"));
        card.put("auth", Map.of(
                "schemes", List.of("InternalToken", "DelegationToken"),
                "headers", List.of("X-Internal-Token", "X-Actor-Id", "X-Actor-Roles",
                        "X-Delegation-Token", "X-Trace-Id")));
        card.put("features", Map.of(
                "cancel", descriptor.cancelSupported(),
                "asyncDurability", descriptor.asyncDurability() == null
                        ? "unknown" : descriptor.asyncDurability(),
                "confirmation", true,
                "streaming", false));
        return card;
    }

    private Map<String, Object> capabilityView(Capability capability) {
        return Map.of(
                "capabilityId", capability.capabilityId(),
                "name", capability.displayName(),
                "description", capability.description() == null ? "" : capability.description(),
                "readOnly", capability.readOnly(),
                "requiresConfirmation", capability.requiresConfirmation(),
                "executionModes", capability.executionModes());
    }

    private AgentDescriptor descriptor(String agentKey) {
        return registry.descriptors().stream()
                .filter(descriptor -> descriptor.agentKey().equals(agentKey))
                .findFirst()
                .orElse(null);
    }

    private Map<String, Object> health(String agentKey) {
        try {
            AgentHealth health = registry.get(agentKey).health();
            return Map.of(
                    "status", health.health(),
                    "ready", health.ready(),
                    "latencyMs", health.latencyMs());
        } catch (Exception ex) {
            return Map.of("status", "UNKNOWN", "ready", "UNKNOWN", "latencyMs", -1);
        }
    }

    private String trace(String traceId) {
        return traceId == null || traceId.isBlank() ? ids.traceId() : traceId;
    }
}
