package com.wuye.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * 原型纵向切片测试：真实 HTTP + 内存存储 + Mock Adapter。
 *
 * <p>随机端口仅用于测试，不占用业务端口；不连接 MySQL/Redis/MinIO。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PrototypeSmokeTest {

    private static final Pattern TASK_NO = Pattern.compile("\"taskNo\":\"(wt_[^\"]+)\"");

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper mapper;

    @Test
    void propertyQueryReturnsTickets() throws Exception {
        String session = createSession();
        String sse = send(session, "我有哪些待处理工单？");

        assertTrue(sse.contains("event:route"), sse);
        assertTrue(sse.contains("property.ticket.query"), sse);
        assertTrue(sse.contains("GD20261119000107"), sse);
        assertTrue(sse.contains("event:done"), sse);
    }

    @Test
    void contractRuleListRoutesToContract() throws Exception {
        String session = createSession();
        String sse = send(session, "合同审查规则有哪些？");

        assertTrue(sse.contains("contract.rule.list"), sse);
        assertTrue(sse.contains("R-CON-001"), sse);
    }

    @Test
    void ambiguousKnowledgeAsksForDomain() throws Exception {
        String session = createSession();
        String sse = send(session, "查一下知识库");

        assertTrue(sse.contains("物业知识库") || sse.contains("合同知识库"), sse);
        assertTrue(sse.contains("CLARIFIED"), sse);
    }

    @Test
    void assignRequiresConfirmationAndApproveSucceeds() throws Exception {
        String session = createSession();
        String sse = send(session, "把 GD20261119000107 派给 10002");

        assertTrue(sse.contains("WAITING_CONFIRM"), sse);
        assertTrue(sse.contains("event:confirm"), sse);
        assertTrue(!sse.contains("confirmationToken"), "确认令牌不能出现在 SSE 中");
        String taskNo = extractTaskNo(sse);

        JsonNode data = postForData("/api/v1/tasks/" + taskNo + "/confirm",
                Map.of("decision", "APPROVE", "comment", "同意"));
        assertEquals("SUCCEEDED", data.path("status").asText());
    }

    @Test
    void createRejectBecomesRejected() throws Exception {
        String session = createSession();
        String sse = send(session, "报修：3栋2单元水管漏水");

        assertTrue(sse.contains("WAITING_CONFIRM"), sse);
        String taskNo = extractTaskNo(sse);

        JsonNode data = postForData("/api/v1/tasks/" + taskNo + "/confirm",
                Map.of("decision", "REJECT", "comment", "拒绝"));
        assertEquals("REJECTED", data.path("status").asText());
    }

    @Test
    void contractReviewRunsAsyncAndSucceeds() throws Exception {
        String session = createSession();
        String sse = send(session, "审查合同 10001");

        assertTrue(sse.contains("contract.review"), sse);
        String taskNo = extractTaskNo(sse);

        JsonNode data = pollUntil(taskNo, "SUCCEEDED", 6000);
        assertEquals("SUCCEEDED", data.path("status").asText());
        assertEquals(3, data.path("output").path("findingsCount").asInt());
    }

    @Test
    void contractReviewCanBeCancelled() throws Exception {
        String session = createSession();
        String sse = send(session, "审查合同 10001");
        String taskNo = extractTaskNo(sse);

        JsonNode data = postForData("/api/v1/tasks/" + taskNo + "/cancel",
                Map.of("reason", "测试取消"));
        assertEquals("CANCELLED", data.path("status").asText());
    }

    @Test
    void contractRuleCreateConfirmThenSucceeds() throws Exception {
        String session = createSession();
        String sse = send(session, "合同创建规则：付款节点必须明确");

        assertTrue(sse.contains("contract.rule.create"), sse);
        String taskNo = extractTaskNo(sse);

        JsonNode confirmed = postForData("/api/v1/tasks/" + taskNo + "/confirm",
                Map.of("decision", "APPROVE", "comment", "同意"));
        assertTrue(List.of("PENDING", "RUNNING", "SUCCEEDED").contains(confirmed.path("status").asText()));

        JsonNode data = pollUntil(taskNo, "SUCCEEDED", 6000);
        assertEquals("SUCCEEDED", data.path("status").asText());
    }

    @Test
    void deleteSessionRemovesMessagesAndTasks() throws Exception {
        String session = createSession();
        String sse = send(session, "我有哪些待处理工单？");
        String taskNo = extractTaskNo(sse);

        ResponseEntity<String> deleted = rest.exchange(
                url("/api/v1/chat/sessions/" + session),
                HttpMethod.DELETE,
                new HttpEntity<>(new HttpHeaders()),
                String.class);
        assertEquals(HttpStatus.OK, deleted.getStatusCode(), deleted.getBody());

        assertEquals(HttpStatus.NOT_FOUND, rest.getForEntity(
                url("/api/v1/chat/sessions/" + session), String.class).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, rest.getForEntity(
                url("/api/v1/chat/sessions/" + session + "/messages"), String.class).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, rest.getForEntity(
                url("/api/v1/tasks/" + taskNo), String.class).getStatusCode());
    }

    @Test
    void healthAndReadyAreUp() {
        ResponseEntity<String> health = rest.getForEntity(url("/health"), String.class);
        assertEquals(HttpStatus.OK, health.getStatusCode());
        assertTrue(health.getBody().contains("\"status\":\"UP\""));

        ResponseEntity<String> ready = rest.getForEntity(url("/ready"), String.class);
        assertEquals(HttpStatus.OK, ready.getStatusCode());
        assertTrue(ready.getBody().contains("\"status\":\"UP\""));
    }

    private String createSession() throws Exception {
        ResponseEntity<String> response = rest.postForEntity(url("/api/v1/chat/sessions"),
                jsonEntity(Map.of("title", "测试会话")), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
        return mapper.readTree(response.getBody()).path("data").path("sessionNo").asText();
    }

    private String send(String sessionNo, String content) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM));
        headers.set("X-Actor-Id", "10001");
        String body = mapper.writeValueAsString(Map.of(
                "content", content,
                "clientMessageId", "cm_" + UUID.randomUUID()));
        ResponseEntity<String> response = rest.postForEntity(
                url("/api/v1/chat/sessions/" + sessionNo + "/messages"),
                new HttpEntity<>(body, headers), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
        return response.getBody();
    }

    private JsonNode postForData(String path, Map<String, Object> body) throws Exception {
        ResponseEntity<String> response = rest.postForEntity(url(path), jsonEntity(body), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
        return mapper.readTree(response.getBody()).path("data");
    }

    private JsonNode pollUntil(String taskNo, String status, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        JsonNode last = null;
        while (System.currentTimeMillis() < deadline) {
            ResponseEntity<String> response = rest.getForEntity(url("/api/v1/tasks/" + taskNo), String.class);
            assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
            last = mapper.readTree(response.getBody()).path("data");
            if (status.equals(last.path("status").asText())) {
                return last;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("任务未在 " + timeoutMs + "ms 内进入 " + status + "，最后状态：" + last);
    }

    private String extractTaskNo(String sse) {
        Matcher matcher = TASK_NO.matcher(sse);
        assertTrue(matcher.find(), "SSE 中没有 taskNo：" + sse);
        return matcher.group(1);
    }

    private HttpEntity<String> jsonEntity(Object body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Actor-Id", "10001");
        return new HttpEntity<>(mapper.writeValueAsString(body), headers);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }
}
