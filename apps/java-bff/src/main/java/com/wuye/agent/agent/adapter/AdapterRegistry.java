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

    /** 运行时安装：内置 Agent 保持不动，只追加/替换动态 Agent。 */
    public synchronized void register(AgentAdapter adapter) {
        AgentDescriptor descriptor = adapter.descriptor();
        AgentAdapter previous = adapters.put(descriptor.agentKey(), adapter);
        if (previous != null) {
            previous.descriptor().capabilities()
                    .forEach(capability -> capabilities.remove(capability.capabilityId()));
        }
        descriptor.capabilities()
                .forEach(capability -> capabilities.put(capability.capabilityId(), capability));
    }

    /** 运行时卸载：只移除指定 Agent 及其能力，不影响其他 Agent。 */
    public synchronized AgentAdapter unregister(String agentKey) {
        AgentAdapter removed = adapters.remove(agentKey);
        if (removed != null) {
            removed.descriptor().capabilities()
                    .forEach(capability -> capabilities.remove(capability.capabilityId()));
        }
        return removed;
    }

    public synchronized AgentAdapter get(String agentKey) {
        return adapters.get(agentKey);
    }

    public synchronized Collection<AgentAdapter> all() {
        return List.copyOf(adapters.values());
    }

    public synchronized List<AgentDescriptor> descriptors() {
        return adapters.values().stream().map(AgentAdapter::descriptor).toList();
    }

    public synchronized List<Capability> capabilities() {
        return capabilities.values().stream().filter(Capability::enabled).toList();
    }

    public synchronized List<Capability> capabilitiesForAgent(String agentKey) {
        return capabilities.values().stream()
                .filter(capability -> capability.agentKey().equals(agentKey))
                .filter(Capability::enabled)
                .toList();
    }

    /**
     * Manifest 同步：替换某个 Agent 的能力目录，不动其他 Agent 与 Adapter 实例。
     * 空列表视为无效同步，避免子 Agent 暂时不可达时清空目录。
     */
    public synchronized void replaceCapabilities(String agentKey, List<Capability> incoming) {
        if (agentKey == null || incoming == null || incoming.isEmpty()) {
            return;
        }
        capabilities.entrySet().removeIf(entry -> entry.getValue().agentKey().equals(agentKey));
        for (Capability capability : incoming) {
            capabilities.put(capability.capabilityId(), capability);
        }
    }

    public synchronized Optional<Capability> capability(String capabilityId) {
        Capability capability = capabilities.get(capabilityId);
        return capability != null && capability.enabled() ? Optional.of(capability) : Optional.empty();
    }

    public synchronized AgentAdapter adapterFor(String capabilityId) {
        return capability(capabilityId)
                .map(c -> adapters.get(c.agentKey()))
                .orElse(null);
    }
}
