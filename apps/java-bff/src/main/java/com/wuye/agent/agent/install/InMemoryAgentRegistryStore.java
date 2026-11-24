package com.wuye.agent.agent.install;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 内存注册表：默认 profile / 测试使用。
 *
 * <p>语义与 MySQL 实现一致：save 覆盖，remove 标记 REMOVED。</p>
 */
@Component
@Profile("!mysql")
public class InMemoryAgentRegistryStore implements AgentRegistryStore {

    private final Map<String, AgentRegistryEntry> entries = new ConcurrentHashMap<>();

    @Override
    public void save(AgentRegistryEntry entry) {
        entries.put(entry.agentKey(), entry);
    }

    @Override
    public Optional<AgentRegistryEntry> find(String agentKey) {
        return Optional.ofNullable(entries.get(agentKey));
    }

    @Override
    public List<AgentRegistryEntry> listActive() {
        return entries.values().stream()
                .filter(AgentRegistryEntry::active)
                .toList();
    }

    @Override
    public void remove(String agentKey) {
        AgentRegistryEntry existing = entries.get(agentKey);
        if (existing == null) {
            return;
        }
        entries.put(agentKey, new AgentRegistryEntry(
                existing.agentKey(), existing.displayName(), existing.contractVersion(),
                existing.dialect(), existing.kind(), existing.templateKey(),
                existing.descriptorJson(), existing.baseUrl(), existing.internalToken(),
                existing.audience(), existing.actorId(), existing.roles(), existing.tenantId(),
                existing.useDelegation(), existing.healthPath(), existing.readyPath(),
                existing.manifestPath(), AgentRegistryEntry.STATUS_REMOVED,
                existing.installedBy(), existing.installedAt(), java.time.Instant.now()));
    }
}
