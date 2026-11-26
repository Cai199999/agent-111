package com.wuye.agent.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * 通知中心纵向切片：内部写入 → 用户未读 → 已读 → 去重。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "wuye.notify.token=test-notify-token",
                "wuye.flywheel.root=target/test-flywheel"
        })
class NotificationFlowTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper mapper;

    @Test
    void internalPushThenUserReadAndDedup() throws Exception {
        String seniorToken = login("senior", "senior123");
        Map<String, Object> payload = Map.of(
                "recipientActorId", "20002",
                "sourceAgent", "oa-entry-agent",
                "category", "OA_TODO",
                "title", "OA 待办：请假申请单",
                "content", "单据 LV-20261125-9999 等待您审批",
                "linkUrl", "http://127.0.0.1:8810/workbench?actorId=20002",
                "bizType", "OA_REQUEST",
                "bizId", "LV-20261125-9999",
                "dedupKey", "oa:submitted:LV-20261125-9999:1");

        assertEquals(401, rest.exchange(url("/api/v1/internal/notifications"),
                HttpMethod.POST, new HttpEntity<>(payload, jsonHeaders(null)), String.class)
                .getStatusCode().value());

        ResponseEntity<String> created = rest.exchange(url("/api/v1/internal/notifications"),
                HttpMethod.POST, new HttpEntity<>(payload, jsonHeaders("test-notify-token")),
                String.class);
        assertEquals(200, created.getStatusCode().value(), created.getBody());
        String notificationNo = mapper.readTree(created.getBody())
                .path("data").path("notificationNo").asText();
        assertTrue(notificationNo.startsWith("ntf_"), created.getBody());

        // 同一 dedupKey 再写一次不新增。
        rest.exchange(url("/api/v1/internal/notifications"), HttpMethod.POST,
                new HttpEntity<>(payload, jsonHeaders("test-notify-token")), String.class);

        JsonNode list = mapper.readTree(get("/api/v1/notifications?status=UNREAD", seniorToken))
                .path("data");
        assertEquals(1, list.path("items").size(), list.toString());
        assertEquals(1, list.path("unread").asLong());

        JsonNode read = mapper.readTree(post(
                "/api/v1/notifications/" + notificationNo + "/read", seniorToken, Map.of()));
        assertEquals(0, read.path("data").path("unread").asLong(), read.toString());

        JsonNode unread = mapper.readTree(get("/api/v1/notifications/unread-count", seniorToken));
        assertEquals(0, unread.path("data").path("unread").asLong(), unread.toString());
    }

    private String login(String username, String password) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.postForEntity(url("/api/v1/auth/login"),
                new HttpEntity<>(Map.of("username", username, "password", password), headers),
                String.class);
        assertEquals(200, response.getStatusCode().value(), response.getBody());
        return mapper.readTree(response.getBody()).path("data").path("accessToken").asText();
    }

    private String get(String path, String token) {
        ResponseEntity<String> response = rest.exchange(url(path), HttpMethod.GET,
                new HttpEntity<>(authHeaders(token)), String.class);
        assertEquals(200, response.getStatusCode().value(), response.getBody());
        return response.getBody();
    }

    private String post(String path, String token, Map<String, Object> body) {
        ResponseEntity<String> response = rest.exchange(url(path), HttpMethod.POST,
                new HttpEntity<>(body, authHeaders(token)), String.class);
        assertEquals(200, response.getStatusCode().value(), response.getBody());
        return response.getBody();
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return headers;
    }

    private HttpHeaders jsonHeaders(String internalToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (internalToken != null) {
            headers.set("X-Internal-Token", internalToken);
        }
        return headers;
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }
}
