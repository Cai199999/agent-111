package com.wuye.agent.auth;

import com.wuye.agent.common.ApiException;

/**
 * 当前请求的登录用户。由 AuthFilter 设置并清理。
 */
public final class AuthContext {

    private static final ThreadLocal<AuthUser> CURRENT = new ThreadLocal<>();

    private AuthContext() {
    }

    public static void set(AuthUser user) {
        CURRENT.set(user);
    }

    public static AuthUser get() {
        return CURRENT.get();
    }

    public static AuthUser require() {
        AuthUser user = CURRENT.get();
        if (user == null) {
            throw new ApiException("AUTH_UNAUTHORIZED", 401, "未登录或令牌无效");
        }
        return user;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
