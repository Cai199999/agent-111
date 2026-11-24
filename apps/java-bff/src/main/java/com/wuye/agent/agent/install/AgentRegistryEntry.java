package com.wuye.agent.agent.install;

import java.time.Instant;

/**
 * 运行时安装 Agent 的持久化记录。
 *
 * <p>只承载安装元数据；{@code internalToken} 在 MySQL 存储层加密，
 * 内存存储层保持原值。模板 Agent 没有 {@code baseUrl}/{@code internalToken}。</p>
 */
public record AgentRegistryEntry(
        String agentKey,
        String displayName,
        String contractVersion,
        String dialect,
        String kind,
        String templateKey,
        String descriptorJson,
        String baseUrl,
        String internalToken,
        String audience,
        String actorId,
        String roles,
        String tenantId,
        boolean useDelegation,
        String healthPath,
        String readyPath,
        String manifestPath,
        String status,
        String installedBy,
        Instant installedAt,
        Instant updatedAt
) {
    public static final String KIND_HTTP = "HTTP";
    public static final String KIND_TEMPLATE = "TEMPLATE";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_REMOVED = "REMOVED";

    public boolean active() {
        return STATUS_ACTIVE.equals(status);
    }
}
