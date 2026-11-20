package com.wuye.agent.agent.adapter;

import com.wuye.agent.agent.model.AgentDescriptor;
import com.wuye.agent.agent.model.Capability;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * Adapter 注册表：按 agentKey 路由，按 capabilityId 查能力。
 *
 * <p>新增标准 Agent 时只需新增一个 Spring Bean；方言 Agent 实现 AgentAdapter 即可。
 * 业务层不感知具体实现。</p>
 */
@Component
public class AdapterRegistry {

    private final Map<String, AgentAdapter> adapters = new LinkedHashMap<>();
    private final Map<String, Capability> capabilities = new LinkedHashMap<>();

    public AdapterRegistry(List<AgentAdapter> discovered) {
        for (AgentAdapter adapter : discovered) {
            AgentDescriptor descriptor = adapter.descriptor();
            adapters.put(descriptor.agentKey(), adapter);
            for (Capability capability : descriptor.capabilities()) {
                capabilities.put(capability.capabilityId(), capability);
            }
        }
    }

    public AgentAdapter get(String agentKey) {
        return adapters.get(agentKey);
    }

    public Collection<AgentAdapter> all() {
        return adapters.values();
    }

    public List<AgentDescriptor> descriptors() {
        return adapters.values().stream().map(AgentAdapter::descriptor).toList();
    }

    public List<Capability> capabilities() {
        return capabilities.values().stream().filter(Capability::enabled).toList();
    }

    public Optional<Capability> capability(String capabilityId) {
        Capability capability = capabilities.get(capabilityId);
        return capability != null && capability.enabled() ? Optional.of(capability) : Optional.empty();
    }

    public AgentAdapter adapterFor(String capabilityId) {
        return capability(capabilityId)
                .map(c -> adapters.get(c.agentKey()))
                .orElse(null);
    }
}
