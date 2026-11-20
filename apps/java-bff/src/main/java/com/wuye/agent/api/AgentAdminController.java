package com.wuye.agent.api;

import com.wuye.agent.agent.adapter.AgentAdapter;
import com.wuye.agent.agent.adapter.AdapterRegistry;
import com.wuye.agent.agent.model.AgentDescriptor;
import com.wuye.agent.agent.model.AgentHealth;
import com.wuye.agent.agent.model.Capability;
import com.wuye.agent.auth.AuthContext;
import com.wuye.agent.auth.PolicyService;
import com.wuye.agent.common.ApiException;
import com.wuye.agent.common.ApiResponse;
import com.wuye.agent.common.Ids;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 注册/能力/健康查询。原型只读；生产化再加注册、同步、开关。
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AgentAdminController {

    private final AdapterRegistry registry;
    private final PolicyService policyService;
    private final Ids ids;

    public AgentAdminController(AdapterRegistry registry, PolicyService policyService, Ids ids) {
        this.registry = registry;
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

    private String trace(String traceId) {
        return traceId == null || traceId.isBlank() ? ids.traceId() : traceId;
    }
}
