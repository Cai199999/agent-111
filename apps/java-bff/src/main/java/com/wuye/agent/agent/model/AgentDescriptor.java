package com.wuye.agent.agent.model;

import java.util.List;
import java.util.Optional;

/**
 * 子 Agent 描述。原型阶段由 Mock Adapter 提供；生产化由 Manifest 同步生成。
 */
public record AgentDescriptor(
        String agentKey,
        String displayName,
        String dialect,
        String contractVersion,
        String envelope,
        String healthPath,
        String readyPath,
        boolean cancelSupported,
        String asyncDurability,
        List<Capability> capabilities
) {
    public Optional<Capability> capability(String capabilityId) {
        return capabilities.stream()
                .filter(c -> c.capabilityId().equals(capabilityId))
                .findFirst();
    }
}
