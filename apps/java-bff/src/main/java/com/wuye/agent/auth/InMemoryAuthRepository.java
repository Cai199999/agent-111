package com.wuye.agent.auth;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 内存 profile 的账号/权限存储，默认演示账号 admin/worker/user。
 */
@Component
@Profile("!mysql")
public class InMemoryAuthRepository implements AuthRepository {

    private final Map<String, UserAccount> byUsername = new LinkedHashMap<>();
    private final Map<Long, UserAccount> byId = new LinkedHashMap<>();
    private final Map<Long, Set<String>> userRoles = new LinkedHashMap<>();

    public InMemoryAuthRepository(PasswordEncoder passwordEncoder) {
        long id = 1;
        for (AuthSeed.DemoUser demo : AuthSeed.demoUsers()) {
            UserAccount user = new UserAccount(id, demo.userNo(), demo.username(),
                    passwordEncoder.encode(demo.password()), demo.displayName(),
                    demo.actorId(), "0", true);
            byUsername.put(user.username(), user);
            byId.put(user.id(), user);
            userRoles.put(user.id(), Set.of(demo.role()));
            id++;
        }
    }

    @Override
    public Optional<UserAccount> findByUsername(String username) {
        return Optional.ofNullable(byUsername.get(username));
    }

    @Override
    public Optional<UserAccount> findById(long id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public List<String> rolesFor(long userId) {
        return List.copyOf(userRoles.getOrDefault(userId, Set.of()));
    }

    @Override
    public Set<String> effectivePermissions(Collection<String> roleCodes) {
        Set<String> permissions = new LinkedHashSet<>();
        for (String role : AuthSeed.rolesWithParents(new LinkedHashSet<>(roleCodes))) {
            permissions.addAll(AuthSeed.ROLE_PERMISSIONS.getOrDefault(role, Set.of()));
        }
        return permissions;
    }
}
