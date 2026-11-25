package com.wuye.agent.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

class IdentityAssertionServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void signsActorRolesAndExpiry() throws Exception {
        String secret = "test-secret";
        IdentityAssertionService service = new IdentityAssertionService(mapper, secret);
        AuthUser user = new AuthUser(1L, "U0002", "worker", "物业普通员工",
                "10002", "0", Set.of("WORKER", "BASE"), Set.of());

        String token = service.sign(user);
        assertNotNull(token);
        String[] parts = token.split("\\.");
        assertEquals(2, parts.length);

        JsonNode payload = mapper.readTree(Base64.getUrlDecoder().decode(parts[0]));
        assertEquals("10002", payload.path("actorId").asText());
        assertEquals("agent-task-v1", payload.path("aud").asText());
        assertTrue(payload.path("exp").asLong() > payload.path("iat").asLong());
        assertTrue(payload.path("roles").toString().contains("WORKER"));

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(parts[0].getBytes(StandardCharsets.UTF_8)));
        assertEquals(expected, parts[1]);
    }

    @Test
    void emptySecretDisablesAssertion() {
        IdentityAssertionService service = new IdentityAssertionService(mapper, "");
        assertFalse(service.enabled());
        assertNull(service.sign(new AuthUser(1L, "U1", "u", "u", "1", "0",
                Set.of("USER"), Set.of())));
    }
}
