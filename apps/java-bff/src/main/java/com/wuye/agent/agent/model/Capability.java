package com.wuye.agent.agent.model;

import java.util.List;

/**
 * 能力目录项。capabilityId 全局唯一，taskType 保留子 Agent 原生名称。
 */
public record Capability(
        String capabilityId,
        String agentKey,
        String taskType,
        String namespace,
        String displayName,
        String description,
        boolean readOnly,
        boolean requiresConfirmation,
        List<String> executionModes,
        boolean enabled
) {
    public boolean supportsSync() {
        return executionModes.contains("SYNC");
    }
}
