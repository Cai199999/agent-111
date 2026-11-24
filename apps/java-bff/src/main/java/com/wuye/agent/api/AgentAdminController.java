package com.wuye.agent.api;

import com.wuye.agent.agent.adapter.AgentAdapter;
import com.wuye.agent.agent.adapter.AdapterRegistry;
import com.wuye.agent.agent.install.AgentInstallService;
import com.wuye.agent.agent.model.AgentDescriptor;
import com.wuye.agent.agent.model.AgentHealth;
import com.wuye.agent.agent.model.Capability;
import com.wuye.agent.auth.AuthContext;
import com.wuye.agent.auth.AuthUser;
import com.wuye.agent.auth.PolicyService;
import com.wuye.agent.common.ApiException;
import com.wuye.agent.common.ApiResponse;
import com.wuye.agent.common.Ids;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 目录/能力/健康，以及运行时安装/卸载。
 *
 * <p>安装支持模板与 Agent Card URL；卸载仅允许运行时安装的动态 Agent。
 * Manifest 自动同步/版本校验仍是后续项。</p>
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AgentAdminController {

    private final AdapterRegistry registry;
    private final AgentInstallService installService;
    private final PolicyService policyService;
    private final Ids ids;

    public AgentAdminController(AdapterRegistry registry, AgentInstallService installService,
                                PolicyService policyService, Ids ids) {
        this.registry = registry;
        this.installService = installService;
        this.policyService = policyService;
        this.ids = ids;
    }

    @GetMapping("/agents")
    public ApiResponse<List<AgentDescriptor>> agents(
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        policyService.requireAdmin(AuthContext.require());
        return ApiResponse.ok(registry.descriptors(), trace(traceId));
    }

    @GetMapping("/capabilities")
    public ApiResponse<List<Capability>> capabilities(
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        policyService.requireAdmin(AuthContext.require());
        return ApiResponse.ok(registry.capabilities(), trace(traceId));
    }

    @GetMapping("/agents/{agentKey}/health")
    public ApiResponse<AgentHealth> health(@PathVariable String agentKey,
                                           @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        policyService.requireAdmin(AuthContext.require());
        AgentAdapter adapter = registry.get(agentKey);
        if (adapter == null) {
            throw ApiException.notFound("AGENT_NOT_FOUND", "Agent 未注册：" + agentKey);
        }
        return ApiResponse.ok(adapter.health(), trace(traceId));
    }

    /** 受控安装：模板安装或从 Agent Card URL 安装；仅 ADMIN。 */
    @PostMapping("/agents/install")
    public ApiResponse<Map<String, Object>> install(
            @RequestBody(required = false) InstallRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        AuthUser user = AuthContext.require();
        policyService.requireAdmin(user);
        if (request == null) {
            throw ApiException.badRequest("INSTALL_REQUEST_REQUIRED", "请提供 template 或 cardUrl");
        }
        Map<String, Object> result;
        if (request.template() != null && !request.template().isBlank()) {
            result = installService.installTemplate(request.template(), user.actorId());
        } else {
            result = installService.installFromCardUrl(request.cardUrl(), request.internalToken(),
                    user.actorId());
        }
        return ApiResponse.ok(result, trace(traceId));
    }

    /** 卸载运行时安装的 Agent；内置 Agent 不允许卸载。 */
    @DeleteMapping("/agents/{agentKey}")
    public ApiResponse<Map<String, Object>> uninstall(
            @PathVariable String agentKey,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        policyService.requireAdmin(AuthContext.require());
        return ApiResponse.ok(installService.uninstall(agentKey), trace(traceId));
    }

    public record InstallRequest(String template, String cardUrl, String internalToken) {
    }

    private String trace(String traceId) {
        return traceId == null || traceId.isBlank() ? ids.traceId() : traceId;
    }
}
