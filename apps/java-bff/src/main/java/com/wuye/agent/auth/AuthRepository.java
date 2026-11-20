package com.wuye.agent.auth;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 账号、角色、权限读取接口。内存/MySQL 两套实现，权限均在运行时从存储读取。
 */
public interface AuthRepository {

    Optional<UserAccount> findByUsername(String username);

    Optional<UserAccount> findById(long id);

    List<String> rolesFor(long userId);

    /**
     * 沿角色父子链计算有效权限。
     */
    Set<String> effectivePermissions(Collection<String> roleCodes);
}
