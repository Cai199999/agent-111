package com.wuye.agent.auth;

import com.fasterxml.jackson.databind.JsonNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 非 MySQL profile：不持久化动态权限。
 *
 * <p>mock/测试环境没有权限表，保持“动态 Agent 仅 ADMIN 可用”的旧行为；
 * mysql profile 使用 {@link MySqlDynamicPermissionService}。</p>
 */
@Component
@Profile("!mysql")
public class NoopDynamicPermissionService implements DynamicPermissionService {

    private static final Logger log = LoggerFactory.getLogger(NoopDynamicPermissionService.class);

    @Override
    public void grant(String agentKey, JsonNode permissions, String installedBy) {
        if (permissions != null && !permissions.isMissingNode() && !permissions.isNull()) {
            log.info("mock profile 不持久化动态权限 agent={}", agentKey);
        }
    }

    @Override
    public void revoke(String agentKey) {
        // no-op
    }
}
