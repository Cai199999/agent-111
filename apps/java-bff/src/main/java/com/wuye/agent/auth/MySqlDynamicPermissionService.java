package com.wuye.agent.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.wuye.agent.audit.AuditService;
import com.wuye.agent.common.ApiException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * H3 的 MySQL 实现：把 Agent Card 的 permissions 写入权限表。
 *
 * <p>规则：
 * <ul>
 *   <li>{@code agentUse} 默认授予所有能力声明的角色并集；可用 {@code agentUseRoles} 覆盖；</li>
 *   <li>能力权限码为 {@code capability:<capabilityId>}；</li>
 *   <li>权限来源标记 {@code AGENT:<agentKey>}；与内置权限冲突时拒绝安装；</li>
 *   <li>卸载只删除该来源的权限与角色关联。</li>
 * </ul>
 */
@Component
@Profile("mysql")
public class MySqlDynamicPermissionService implements DynamicPermissionService {

    private static final Logger log = LoggerFactory.getLogger(MySqlDynamicPermissionService.class);
    private static final int MAX_CODE_LENGTH = 128;

    private final JdbcTemplate jdbc;
    private final AuditService auditService;

    public MySqlDynamicPermissionService(JdbcTemplate jdbc, AuditService auditService) {
        this.jdbc = jdbc;
        this.auditService = auditService;
    }

    @Override
    @Transactional
    public void grant(String agentKey, JsonNode permissions, String installedBy) {
        Map<String, PermissionPlan> plans = parse(agentKey, permissions);
        if (plans.isEmpty()) {
            return;
        }
        Set<String> knownRoles = new LinkedHashSet<>(
                jdbc.queryForList("SELECT role_code FROM wa_role", String.class));
        for (PermissionPlan plan : plans.values()) {
            for (String role : plan.roles()) {
                if (!knownRoles.contains(role)) {
                    throw ApiException.badRequest("AGENT_PERMISSION_ROLE_UNKNOWN",
                            "Agent Card 声明了未知角色：" + role);
                }
            }
        }

        String source = source(agentKey);
        for (PermissionPlan plan : plans.values()) {
            List<String> existing = jdbc.queryForList(
                    "SELECT source FROM wa_permission WHERE permission_code = ?",
                    String.class, plan.code());
            if (!existing.isEmpty() && !source.equals(existing.get(0))) {
                throw ApiException.conflict("AGENT_PERMISSION_CONFLICT",
                        "权限码已被内置或其他 Agent 使用：" + plan.code());
            }
            jdbc.update("""
                            INSERT INTO wa_permission (permission_code, description, source)
                            VALUES (?, ?, ?)
                            ON DUPLICATE KEY UPDATE
                                description = VALUES(description),
                                source = VALUES(source)
                            """,
                    plan.code(), plan.description(), source);
            // 以 Card 声明为准重置角色关联；内置权限已在上面拒绝。
            jdbc.update("DELETE FROM wa_role_permission WHERE permission_code = ?", plan.code());
            for (String role : plan.roles()) {
                jdbc.update("INSERT IGNORE INTO wa_role_permission (role_code, permission_code) "
                        + "VALUES (?, ?)", role, plan.code());
            }
        }
        auditService.recordAs(installedBy, null, "AGENT_PERMISSIONS_GRANT", "agent", agentKey,
                agentKey, null, "SUCCEEDED", Map.of("permissions", plans.keySet()));
        log.info("granted dynamic permissions agent={} count={}", agentKey, plans.size());
    }

    @Override
    @Transactional
    public void revoke(String agentKey) {
        String source = source(agentKey);
        List<String> codes = jdbc.queryForList(
                "SELECT permission_code FROM wa_permission WHERE source = ?", String.class, source);
        if (codes.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", Collections.nCopies(codes.size(), "?"));
        jdbc.update("DELETE FROM wa_role_permission WHERE permission_code IN (" + placeholders + ")",
                codes.toArray());
        jdbc.update("DELETE FROM wa_permission WHERE source = ?", source);
        auditService.record("AGENT_PERMISSIONS_REVOKE", "agent", agentKey, "SUCCEEDED",
                Map.of("permissions", codes));
        log.info("revoked dynamic permissions agent={} count={}", agentKey, codes.size());
    }

    /**
     * 解析 Card 权限声明。包可见，便于单测；不访问数据库。
     */
    static Map<String, PermissionPlan> parse(String agentKey, JsonNode permissions) {
        Map<String, PermissionPlan> plans = new LinkedHashMap<>();
        if (permissions == null || permissions.isMissingNode() || permissions.isNull()) {
            return plans;
        }
        Set<String> allCapabilityRoles = new LinkedHashSet<>();
        JsonNode capabilities = permissions.get("capabilities");
        if (capabilities != null && capabilities.isObject()) {
            capabilities.fields().forEachRemaining(entry -> {
                List<String> roles = roles(entry.getValue());
                if (roles.isEmpty()) {
                    return;
                }
                String capabilityId = entry.getKey();
                String code = "capability:" + capabilityId;
                requireCodeLength(code);
                plans.put(code, new PermissionPlan(code,
                        "Agent " + agentKey + " 能力：" + capabilityId, roles));
                allCapabilityRoles.addAll(roles);
            });
        }

        String agentUse = text(permissions.get("agentUse"));
        if (agentUse == null || agentUse.isBlank()) {
            agentUse = "agent:" + agentKey + ":use";
        }
        if (!agentUse.startsWith("agent:")) {
            throw ApiException.badRequest("AGENT_PERMISSION_INVALID",
                    "agentUse 必须以 agent: 开头：" + agentUse);
        }
        requireCodeLength(agentUse);
        List<String> agentUseRoles = roles(permissions.get("agentUseRoles"));
        if (agentUseRoles.isEmpty()) {
            agentUseRoles = new ArrayList<>(allCapabilityRoles);
        }
        if (!agentUseRoles.isEmpty()) {
            plans.put(agentUse, new PermissionPlan(agentUse,
                    "使用 Agent " + agentKey, List.copyOf(agentUseRoles)));
        }
        return plans;
    }

    private static List<String> roles(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return List.of();
        }
        List<String> roles = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                String role = text(item);
                if (role != null && !role.isBlank() && !roles.contains(role)) {
                    roles.add(role);
                }
            }
        } else {
            String role = text(node);
            if (role != null && !role.isBlank()) {
                roles.add(role);
            }
        }
        return roles;
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        String text = node.asText();
        return text == null || text.isBlank() ? null : text;
    }

    private static void requireCodeLength(String code) {
        if (code.length() > MAX_CODE_LENGTH) {
            throw ApiException.badRequest("AGENT_PERMISSION_INVALID",
                    "权限码超过 " + MAX_CODE_LENGTH + " 字符：" + code);
        }
    }

    private static String source(String agentKey) {
        return "AGENT:" + agentKey;
    }

    record PermissionPlan(String code, String description, List<String> roles) {
    }
}
