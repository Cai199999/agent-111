package com.wuye.agent.agent.catalog;

import com.wuye.agent.agent.adapter.AdapterRegistry;
import com.wuye.agent.agent.model.AgentDescriptor;
import com.wuye.agent.agent.model.Capability;
import com.wuye.agent.auth.AuthUser;
import com.wuye.agent.auth.PolicyService;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

/**
 * 按当前用户权限裁剪 Agent/能力目录。前端只看到后端允许的卡片。
 */
@Service
public class AgentCatalogService {

    private final AdapterRegistry registry;
    private final PolicyService policyService;

    public AgentCatalogService(AdapterRegistry registry, PolicyService policyService) {
        this.registry = registry;
        this.policyService = policyService;
    }

    public List<AgentView> forUser(AuthUser user) {
        List<AgentView> result = new ArrayList<>();
        for (AgentDescriptor descriptor : registry.descriptors()) {
            if (!policyService.canUseAgent(user, descriptor.agentKey())) {
                continue;
            }
            List<CapabilityView> capabilities = policyService
                    .allowedCapabilities(user, descriptor.capabilities())
                    .stream()
                    .map(AgentCatalogService::view)
                    .toList();
            if (capabilities.isEmpty()) {
                continue;
            }
            result.add(new AgentView(
                    descriptor.agentKey(),
                    descriptor.displayName(),
                    descriptor.dialect(),
                    descriptor.contractVersion(),
                    descriptor.cancelSupported(),
                    descriptor.asyncDurability(),
                    capabilities));
        }
        return result;
    }

    public List<Capability> capabilitiesForUser(AuthUser user) {
        return policyService.allowedCapabilities(user, registry.capabilities());
    }

    private static CapabilityView view(Capability capability) {
        return new CapabilityView(
                capability.capabilityId(),
                capability.displayName(),
                capability.description(),
                capability.readOnly(),
                capability.requiresConfirmation(),
                capability.executionModes());
    }

    public record AgentView(
            String agentKey,
            String displayName,
            String dialect,
            String contractVersion,
            boolean cancelSupported,
            String asyncDurability,
            List<CapabilityView> capabilities
    ) {
    }

    public record CapabilityView(
            String capabilityId,
            String displayName,
            String description,
            boolean readOnly,
            boolean requiresConfirmation,
            List<String> executionModes
    ) {
    }
}
