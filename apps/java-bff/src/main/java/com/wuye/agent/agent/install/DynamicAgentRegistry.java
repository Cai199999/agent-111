package com.wuye.agent.agent.install;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 运行时安装的 Agent 登记簿。
 *
 * <p>只记录“哪些 Agent/能力是动态安装的”，用于：
 * 卸载、UI 标识、以及管理员对动态能力的临时使用授权。
 * 不做任意代码执行，也不改变内置 Agent。</p>
 */
@Component
public class DynamicAgentRegistry {

    private final Set<String> agentKeys = ConcurrentHashMap.newKeySet();
    private final Set<String> capabilityIds = ConcurrentHashMap.newKeySet();

    public void markInstalled(String agentKey, Collection<String> capabilities) {
        if (agentKey == null || agentKey.isBlank()) {
            return;
        }
        agentKeys.add(agentKey);
        if (capabilities != null) {
            capabilityIds.addAll(capabilities);
        }
    }

    public void markRemoved(String agentKey, Collection<String> capabilities) {
        if (agentKey != null) {
            agentKeys.remove(agentKey);
        }
        if (capabilities != null) {
            capabilityIds.removeAll(capabilities);
        }
    }

    public boolean isDynamic(String agentKey) {
        return agentKey != null && agentKeys.contains(agentKey);
    }

    public boolean isDynamicCapability(String capabilityId) {
        return capabilityId != null && capabilityIds.contains(capabilityId);
    }

    public Set<String> agentKeys() {
        return Set.copyOf(agentKeys);
    }
}
