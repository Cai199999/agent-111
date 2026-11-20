package com.wuye.agent.memory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!mysql")
public class InMemoryUserProfileStore implements UserProfileStore {

    private final Map<Long, UserMemoryProfile> profiles = new ConcurrentHashMap<>();

    @Override
    public Optional<UserMemoryProfile> get(long userId) {
        return Optional.ofNullable(profiles.get(userId));
    }

    @Override
    public void save(UserMemoryProfile profile) {
        profiles.put(profile.userId(), profile);
    }

    @Override
    public void delete(long userId) {
        profiles.remove(userId);
    }
}
