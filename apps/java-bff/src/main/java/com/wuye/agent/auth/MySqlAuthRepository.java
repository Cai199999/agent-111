package com.wuye.agent.auth;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * MySQL profile 的账号/角色/权限读取。
 */
@Component
@Profile("mysql")
public class MySqlAuthRepository implements AuthRepository {

    private final JdbcTemplate jdbc;

    public MySqlAuthRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UserAccount> findByUsername(String username) {
        return queryUser("SELECT * FROM wa_user WHERE username = ? AND status = 1", username);
    }

    @Override
    public Optional<UserAccount> findById(long id) {
        return queryUser("SELECT * FROM wa_user WHERE id = ? AND status = 1", id);
    }

    @Override
    public List<String> rolesFor(long userId) {
        return jdbc.queryForList("SELECT role_code FROM wa_user_role WHERE user_id = ?",
                String.class, userId);
    }

    @Override
    public Set<String> effectivePermissions(Collection<String> roleCodes) {
        Set<String> roles = new LinkedHashSet<>(roleCodes);
        List<MapRole> allRoles = jdbc.query("SELECT role_code, parent_code FROM wa_role",
                (rs, rowNum) -> new MapRole(rs.getString("role_code"), rs.getString("parent_code")));
        boolean changed = true;
        while (changed) {
            changed = false;
            for (MapRole role : allRoles) {
                if (roles.contains(role.roleCode()) && role.parentCode() != null
                        && roles.add(role.parentCode())) {
                    changed = true;
                }
            }
        }
        if (roles.isEmpty()) {
            return Set.of();
        }
        String placeholders = String.join(",", roles.stream().map(r -> "?").toList());
        List<String> permissions = jdbc.queryForList(
                "SELECT permission_code FROM wa_role_permission WHERE role_code IN (" + placeholders + ")",
                String.class, roles.toArray());
        return new LinkedHashSet<>(permissions);
    }

    private Optional<UserAccount> queryUser(String sql, Object... args) {
        List<UserAccount> rows = jdbc.query(sql, (rs, rowNum) -> new UserAccount(
                rs.getLong("id"),
                rs.getString("user_no"),
                rs.getString("username"),
                rs.getString("password_hash"),
                rs.getString("display_name"),
                rs.getString("actor_id"),
                rs.getString("tenant_id"),
                rs.getBoolean("status")), args);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private record MapRole(String roleCode, String parentCode) {
    }
}
