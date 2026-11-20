package com.wuye.agent.auth;

/**
 * 登录账号（含密码哈希，仅认证层使用，绝不返回前端）。
 */
public record UserAccount(
        long id,
        String userNo,
        String username,
        String passwordHash,
        String displayName,
        String actorId,
        String tenantId,
        boolean enabled
) {
}
