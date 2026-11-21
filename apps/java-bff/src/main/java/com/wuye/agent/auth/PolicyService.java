package com.wuye.agent.auth;

import com.wuye.agent.agent.install.DynamicAgentRegistry;
import com.wuye.agent.agent.model.Capability;
import com.wuye.agent.common.ApiException;
import java.util.List;

import org.springframework.stereotype.Service;

/**
 * 权限判断统一入口。权限码来自数据库，不在业务代码里硬编码角色名。
 */
@Service
public class PolicyService {

    private final DynamicAgentRegistry dynamicAgentRegistry;

    public PolicyService(DynamicAgentRegistry dynamicAgentRegistry) {
        this.dynamicAgentRegistry = dynamicAgentRegistry;
    }

    public boolean canUseCapability(AuthUser user, String capabilityId) {
        // 运行时安装的 Agent 能力：管理员可临时使用（演示态），
        // 生产化应改为安装审批 + 角色授权。
        if (user != null && user.isAdmin()
                && dynamicAgentRegistry.isDynamicCapability(capabilityId)) {
            return true;
        }
        return user != null && user.hasPermission("capability:" + capabilityId);
    }

    public boolean canUseAgent(AuthUser user, String agentKey) {
        return switch (agentKey) {
            case "property-maintenance" -> user != null && user.hasPermission("agent:property:use");
            case "contract-review" -> user != null && user.hasPermission("agent:contract:use");
            // 新 Agent 默认走约定权限 agent:<agentKey>:use，避免每接一个 Agent 就改这里。
            default -> user != null && (user.isAdmin()
                    || user.hasPermission("agent:" + agentKey + ":use"));
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

    public void requirePermission(AuthUser user, String permission) {
        if (user == null || !user.hasPermission(permission)) {
            throw new ApiException("AUTH_FORBIDDEN", 403, "当前账号缺少权限：" + permission);
        }
    }

    public void requireAdmin(AuthUser user) {
        if (user == null || !user.isAdmin()) {
            throw new ApiException("AUTH_FORBIDDEN", 403, "需要管理员权限");
        }
    }
}
