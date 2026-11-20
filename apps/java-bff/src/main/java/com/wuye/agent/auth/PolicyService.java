package com.wuye.agent.auth;

import com.wuye.agent.agent.model.Capability;
import com.wuye.agent.common.ApiException;
import java.util.List;

import org.springframework.stereotype.Service;

/**
 * 权限判断统一入口。权限码来自数据库，不在业务代码里硬编码角色名。
 */
@Service
public class PolicyService {

    public boolean canUseCapability(AuthUser user, String capabilityId) {
        return user != null && user.hasPermission("capability:" + capabilityId);
    }

    public boolean canUseAgent(AuthUser user, String agentKey) {
        return switch (agentKey) {
            case "property-maintenance" -> user != null && user.hasPermission("agent:property:use");
            case "contract-review" -> user != null && user.hasPermission("agent:contract:use");
            default -> user != null && user.isAdmin();
        };
    }

    public List<Capability> allowedCapabilities(AuthUser user, List<Capability> capabilities) {
        return capabilities.stream()
                .filter(Capability::enabled)
                .filter(capability -> canUseAgent(user, capability.agentKey()))
                .filter(capability -> canUseCapability(user, capability.capabilityId()))
                .toList();
    }

    public void requireCapability(AuthUser user, String capabilityId) {
        if (!canUseCapability(user, capabilityId)) {
            throw new ApiException("AUTH_FORBIDDEN", 403, "当前账号无权使用能力：" + capabilityId);
        }
    }

    public void requireAdmin(AuthUser user) {
        if (user == null || !user.isAdmin()) {
            throw new ApiException("AUTH_FORBIDDEN", 403, "需要管理员权限");
        }
    }
}
