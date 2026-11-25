package com.wuye.agent.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class AuthSeedTest {

    @Test
    void demoUsersCoverEveryBusinessLevelWithUniqueActorIds() {
        List<AuthSeed.DemoUser> users = AuthSeed.demoUsers();
        assertEquals(6, users.size());

        Set<String> usernames = new HashSet<>();
        Set<String> actorIds = new HashSet<>();
        for (AuthSeed.DemoUser user : users) {
            assertTrue(usernames.add(user.username()), "用户名重复：" + user.username());
            assertTrue(actorIds.add(user.actorId()), "actorId 重复：" + user.actorId());
        }

        assertTrue(usernames.containsAll(
                Set.of("admin", "worker", "user", "senior", "contractor", "supervisor")));
        assertTrue(actorIds.containsAll(
                Set.of("10001", "10002", "10003", "20002", "20003", "20004")));
    }

    @Test
    void workerAndAdminRolesInheritBase() {
        assertEquals(Set.of("BASE", "WORKER", "ADMIN"),
                AuthSeed.rolesWithParents(Set.of("ADMIN")));
    }
}
