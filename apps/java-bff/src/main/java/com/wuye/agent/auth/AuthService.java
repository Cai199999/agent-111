package com.wuye.agent.auth;

import com.wuye.agent.common.ApiException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final AuthRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(AuthRepository repository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public LoginResult login(String username, String password) {
        UserAccount account = repository.findByUsername(username)
                .filter(UserAccount::enabled)
                .orElseThrow(() -> new ApiException("AUTH_UNAUTHORIZED", 401, "用户名或密码错误"));
        if (!passwordEncoder.matches(password, account.passwordHash())) {
            throw new ApiException("AUTH_UNAUTHORIZED", 401, "用户名或密码错误");
        }
        AuthUser user = toAuthUser(account);
        String token = jwtService.issue(user);
        return new LoginResult(token, jwtService.ttlSeconds(), user);
    }

    public AuthUser loadUser(long userId) {
        UserAccount account = repository.findById(userId)
                .filter(UserAccount::enabled)
                .orElseThrow(() -> new ApiException("AUTH_UNAUTHORIZED", 401, "用户不存在或已禁用"));
        return toAuthUser(account);
    }

    private AuthUser toAuthUser(UserAccount account) {
        List<String> roles = repository.rolesFor(account.id());
        Set<String> permissions = repository.effectivePermissions(roles);
        return new AuthUser(account.id(), account.userNo(), account.username(),
                account.displayName(), account.actorId(), account.tenantId(),
                new LinkedHashSet<>(roles), permissions);
    }

    public record LoginResult(String accessToken, long expiresIn, AuthUser user) {
    }
}
