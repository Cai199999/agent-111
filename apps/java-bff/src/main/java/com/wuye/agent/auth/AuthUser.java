package com.wuye.agent.auth;

import java.util.Set;

/**
 * 当前登录用户（不含密码），由过滤器放入 AuthContext。
 */
public record AuthUser(
        long id,
        String userNo,
        String username,
        String displayName,
        String actorId,
        String tenantId,
        Set<String> roles,
        Set<String> permissions
) {
    public boolean hasPermission(String permission) {
        return permissions.contains(permission);
    }

    public boolean isAdmin() {
        return roles.contains("ADMIN");
    }
}
