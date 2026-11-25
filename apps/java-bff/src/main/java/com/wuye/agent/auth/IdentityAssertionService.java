package com.wuye.agent.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 底座 → 子 Agent 的签名身份断言。
 *
 * <p>只签名 actorId、角色、租户和短过期时间；子 Agent 仍需用自己的绑定表
 * 决定业务权限。密钥为空时不生成，保持向后兼容。</p>
 */
@Service
public class IdentityAssertionService {

    public static final String HEADER = "X-Identity-Assertion";
    public static final String AUDIENCE = "agent-task-v1";
    private static final long TTL_SECONDS = 120;

    private final ObjectMapper mapper;
    private final String secret;

    public IdentityAssertionService(
            ObjectMapper mapper,
            @Value("${wuye.identity.assertion-secret:}") String secret) {
        this.mapper = mapper;
        this.secret = secret == null ? "" : secret;
    }

    public boolean enabled() {
        return !secret.isBlank();
    }

    public String sign(AuthUser user) {
        if (!enabled() || user == null) {
            return null;
        }
        try {
            long now = Instant.now().getEpochSecond();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("actorId", user.actorId());
            payload.put("roles", List.copyOf(new TreeSet<>(user.roles())));
            payload.put("tenantId", user.tenantId());
            payload.put("aud", AUDIENCE);
            payload.put("iat", now);
            payload.put("exp", now + TTL_SECONDS);
            String body = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mapper.writeValueAsBytes(payload));
            String signature = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(hmac(body));
            return body + "." + signature;
        } catch (Exception ex) {
            return null;
        }
    }

    private byte[] hmac(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
    }
}
