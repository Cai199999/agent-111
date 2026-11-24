package com.wuye.agent.auth;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * H3：Agent Card 权限声明。
 *
 * <p>安装时按 Card 的 {@code permissions} 写入权限与角色关联；
 * 卸载时只撤销该 Agent 来源的权限。未声明时不写任何权限，
 * 保持“动态 Agent 仅 ADMIN 可用”的向后兼容。</p>
 */
public interface DynamicPermissionService {

    /**
     * @param agentKey    Agent 标识
     * @param permissions Card 的 permissions 节点，可为 null/缺失
     * @param installedBy 安装人（审计用）
     */
    void grant(String agentKey, JsonNode permissions, String installedBy);

    /** 撤销该 Agent 来源的全部权限与角色关联。 */
    void revoke(String agentKey);
}
