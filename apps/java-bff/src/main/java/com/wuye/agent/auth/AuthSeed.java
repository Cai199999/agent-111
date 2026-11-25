package com.wuye.agent.auth;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 内存 profile 的默认角色/权限/演示账号。
 * MySQL profile 的角色权限由 Flyway V3 种子提供，账号由 AuthBootstrap 创建。
 */
public final class AuthSeed {

    private AuthSeed() {
    }

    public static final Map<String, String> ROLE_PARENT = new LinkedHashMap<>();

    static {
        ROLE_PARENT.put("BASE", null);
        ROLE_PARENT.put("USER", "BASE");
        ROLE_PARENT.put("WORKER", "BASE");
        ROLE_PARENT.put("ADMIN", "WORKER");
    }

    public static final Map<String, Set<String>> ROLE_PERMISSIONS = new LinkedHashMap<>();

    static {
        ROLE_PERMISSIONS.put("BASE", Set.of(
                "agent:property:use",
                "agent:geo-asset-agent:use",
                "notification:read",
                "capability:property.masterdata.options",
                "capability:property.profile.get",
                "capability:property.knowledge.search",
                "capability:property.ticket.similar",
                "capability:gis.parcel.query",
                "capability:gis.change.query"));
        ROLE_PERMISSIONS.put("USER", Set.of(
                "capability:property.profile.update",
                "capability:property.ticket.create",
                "capability:property.ticket.query",
                "capability:property.ticket.detail"));
        ROLE_PERMISSIONS.put("WORKER", Set.of(
                "human:manage",
                "capability:gis.patrol.create",
                "capability:property.ticket.query",
                "capability:property.ticket.detail",
                "capability:property.ticket.accept",
                "capability:property.ticket.resolve",
                "capability:property.ticket.comment.add",
                "capability:property.ticket.material.add",
                "capability:property.ticket.visit.add",
                "capability:property.ticket.hold",
                "capability:property.ticket.resume",
                "capability:property.ticket.finish",
                "capability:property.ticket.close",
                "capability:property.ticket.verify"));
        ROLE_PERMISSIONS.put("ADMIN", Set.of(
                "agent:contract:use",
                "admin:agent:manage",
                "admin:user:review",
                "capability:property.profile.update",
                "capability:property.ticket.create",
                "capability:property.ticket.assign",
                "capability:property.ticket.cancel",
                "capability:property.ticket.export",
                "capability:contract.review",
                "capability:contract.resolve",
                "capability:contract.rule.create",
                "capability:contract.rule.list",
                "capability:contract.knowledge.search"));
    }

    public record DemoUser(String userNo, String username, String password,
                           String displayName, String actorId, String role) {
    }

    /**
     * 演示账号按“层级”而不是具体人建：同一层级可以有多人，业务身份由各 Agent
     * 用 actorId 做本地绑定。这里只保证每个层级都有可登录的样例账号。
     */
    public static List<DemoUser> demoUsers() {
        return List.of(
                new DemoUser("U0001", "admin", "admin123", "管理员", "10001", "ADMIN"),
                new DemoUser("U0002", "worker", "worker123", "物业普通员工", "10002", "WORKER"),
                new DemoUser("U0003", "user", "user123", "租户/用户", "10003", "USER"),
                new DemoUser("U0004", "senior", "senior123", "物业高级员工", "20002", "WORKER"),
                new DemoUser("U0005", "contractor", "contractor123", "施工工人", "20003", "WORKER"),
                new DemoUser("U0006", "supervisor", "supervisor123", "监理", "20004", "WORKER"));
    }

    public static Set<String> rolesWithParents(Set<String> roles) {
        Set<String> result = new LinkedHashSet<>();
        for (String role : roles) {
            String current = role;
            while (current != null && result.add(current)) {
                current = ROLE_PARENT.get(current);
            }
        }
        return result;
    }
}
