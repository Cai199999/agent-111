package com.wuye.agent.memory;

import java.util.Optional;

public interface UserProfileStore {

    Optional<UserMemoryProfile> get(long userId);

    void save(UserMemoryProfile profile);

    void delete(long userId);
}
