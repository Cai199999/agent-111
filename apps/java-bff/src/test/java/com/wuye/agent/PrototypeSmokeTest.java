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

import org.junit.jupiter.api.BeforeEach;
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

    private String adminToken;

    @BeforeEach
    void loginAdmin() throws Exception {
        adminToken = login("admin", "admin123");
    }

    @Test
    void loginAndPermissionCatalogWork() throws Exception {
        assertEquals(401, rest.postForEntity(url("/api/v1/auth/login"),
                jsonEntity(Map.of("username", "admin", "password", "wrong")), String.class)
                .getStatusCode().value());
        assertEquals(401, rest.getForEntity(url("/api/v1/chat/sessions"), String.class)
                .getStatusCode().value());

        String userToken = login("user", "user123");
        String workerToken = login("worker", "worker123");

        String adminAgents = getWithToken("/api/v1/agents", adminToken);
        String userAgents = getWithToken("/api/v1/agents", userToken);
        String workerAgents = getWithToken("/api/v1/agents", workerToken);

        assertTrue(adminAgents.contains("contract-review"), adminAgents);
        assertTrue(adminAgents.contains("property-maintenance"), adminAgents);
        assertTrue(!userAgents.contains("contract-review"), userAgents);
        assertTrue(!workerAgents.contains("contract-review"), workerAgents);
        assertTrue(userAgents.contains("property.ticket.create"), userAgents);
        assertTrue(!workerAgents.contains("property.ticket.create"), workerAgents);
        assertTrue(workerAgents.contains("property.ticket.accept"), workerAgents);
    }

    @Test
    void profileFormAndUpdateFlowWork() throws Exception {
        String form = getWithToken("/api/v1/profile/form", adminToken);
        assertTrue(form.contains("contactName"), form);
        assertTrue(form.contains("profile.update"), form);

        String options = getWithToken("/api/v1/profile/options", adminToken);
        assertTrue(options.contains("projects"), options);

        String profile = getWithToken("/api/v1/profile", adminToken);
        assertTrue(profile.contains("phoneMask"), profile);

        JsonNode task = postForData("/api/v1/profile", Map.of(
                "contactName", "张三",
                "phone", "13800001234",
                "projectId", 100,
                "buildingId", 101,
                "roomId", 1001,
                "customerId", 2001));
        assertEquals("WAITING_CONFIRM", task.path("status").asText(), task.toString());
        String taskNo = task.path("taskNo").asText();

        JsonNode confirmed = postForData("/api/v1/tasks/" + taskNo + "/confirm",
                Map.of("decision", "APPROVE", "comment", "保存"));
        assertEquals("SUCCEEDED", confirmed.path("status").asText(), confirmed.toString());
        assertEquals("APPROVED", confirmed.path("output").path("reviewStatus").asText());
    }

    @Test
    void propertyQueryReturnsTickets() throws Exception {
        String session = createSession();
        String sse = send(session, "我有哪些待处理工单？");

        assertTrue(sse.contains("event:route"), sse);
        assertTrue(sse.contains("property.ticket.query"), sse);
        assertTrue(sse.contains("event:result"), sse);
        assertTrue(sse.contains("GD20261119000107"), sse);
        assertTrue(sse.contains("event:done"), sse);
    }

    @Test
    void adminMetricsSummaryWorks() throws Exception {
        String metrics = getWithToken("/api/v1/admin/metrics/summary", adminToken);
        assertTrue(metrics.contains("\"tasks\""), metrics);
        assertTrue(metrics.contains("\"funnel\""), metrics);
        assertTrue(metrics.contains("\"humanIntervention\""), metrics);
        assertTrue(metrics.contains("\"llmUsage\""), metrics);

        String userToken = login("user", "user123");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(userToken);
        ResponseEntity<String> forbidden = rest.exchange(
                url("/api/v1/admin/metrics/summary"), HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatusCode());
    }

    @Test
    void adminAuditLogsContainLoginAndTaskActions() throws Exception {
        String session = createSession();
        send(session, "我有哪些待处理工单？");

        String logs = getWithToken("/api/v1/admin/audit-logs?limit=50", adminToken);
        assertTrue(logs.contains("\"LOGIN\""), logs);
        assertTrue(logs.contains("\"TASK_SUBMIT\""), logs);

        String userToken = login("user", "user123");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(userToken);
        ResponseEntity<String> forbidden = rest.exchange(
                url("/api/v1/admin/audit-logs"), HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatusCode());
    }

    @Test
    void humanQueueCanBeClaimedAndResolved() throws Exception {
        String session = createSession();
        String sse = send(session, "报修：3栋2单元水管漏水");
        String taskNo = extractTaskNo(sse);

        String queue = getWithToken("/api/v1/human-tasks?status=PENDING", adminToken);
        assertTrue(queue.contains(taskNo), queue);

        JsonNode claimed = postForData("/api/v1/human-tasks/" + taskNo + "/claim", Map.of());
        assertEquals("CLAIMED", claimed.path("human").path("status").asText());

        JsonNode resolved = postForData("/api/v1/human-tasks/" + taskNo + "/resolve",
                Map.of("note", "人工处理完成"));
        assertEquals("RESOLVED", resolved.path("human").path("status").asText());
    }

    @Test
    void profileMemoryCanBeReadAndCleared() throws Exception {
        String memory = getWithToken("/api/v1/profile/memory", adminToken);
        assertTrue(memory.contains("\"profile\""), memory);

        HttpHeaders headers = authHeaders();
        ResponseEntity<String> cleared = rest.exchange(url("/api/v1/profile/memory"),
                HttpMethod.DELETE, new HttpEntity<>(headers), String.class);
        assertEquals(HttpStatus.OK, cleared.getStatusCode(), cleared.getBody());
    }

    @Test
    void toolCatalogAndExecuteReturnRawOutput() throws Exception {
        String tools = getWithToken("/api/v1/tools", adminToken);
        assertTrue(tools.contains("property.ticket.query"), tools);
        assertTrue(tools.contains("inputSchema"), tools);

        JsonNode task = postForData("/api/v1/tools/property.ticket.query/execute", Map.of());
        assertEquals("SUCCEEDED", task.path("status").asText(), task.toString());
        assertTrue(task.path("output").isArray(), task.toString());
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
    void missingCriticalInfoAsksUserInsteadOfRawError() throws Exception {
        String session = createSession();
        String sse = send(session, "报修：花园的喷泉坏了");

        // 缺工单类型 -> 自修复应追问用户，而不是把 VALIDATION 原始错误抛给用户。
        assertTrue(sse.contains("CLARIFIED"), sse);
        assertTrue(sse.contains("确认一下") || sse.contains("工单类型"), sse);
        assertTrue(!sse.contains("event:error"), sse);
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

        HttpHeaders deleteHeaders = new HttpHeaders();
        deleteHeaders.setBearerAuth(adminToken);
        ResponseEntity<String> deleted = rest.exchange(
                url("/api/v1/chat/sessions/" + session),
                HttpMethod.DELETE,
                new HttpEntity<>(deleteHeaders),
                String.class);
        assertEquals(HttpStatus.OK, deleted.getStatusCode(), deleted.getBody());

        assertEquals(HttpStatus.NOT_FOUND, rest.exchange(
                url("/api/v1/chat/sessions/" + session), HttpMethod.GET,
                new HttpEntity<>(authHeaders()), String.class).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, rest.exchange(
                url("/api/v1/chat/sessions/" + session + "/messages"), HttpMethod.GET,
                new HttpEntity<>(authHeaders()), String.class).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, rest.exchange(
                url("/api/v1/tasks/" + taskNo), HttpMethod.GET,
                new HttpEntity<>(authHeaders()), String.class).getStatusCode());
    }

    @Test
    void a2aCardsExposeProtocolAndCapabilities() throws Exception {
        // 公开 Agent Card：无需登录、脱敏、可发现
        ResponseEntity<String> publicCard = rest.getForEntity(
                url("/.well-known/agent-card.json"), String.class);
        assertEquals(HttpStatus.OK, publicCard.getStatusCode());
        assertTrue(publicCard.getBody().contains("wuye-main-agent"), publicCard.getBody());
        assertTrue(publicCard.getBody().contains("agent-task-v1"), publicCard.getBody());
        assertTrue(publicCard.getBody().contains("discovery-only"), publicCard.getBody());
        assertTrue(!publicCard.getBody().contains("internalToken"), publicCard.getBody());
        assertTrue(!publicCard.getBody().contains("DEEPSEEK"), publicCard.getBody());

        // 子 Agent Card：按权限可见，含能力与方言
        String adminCard = getWithToken("/api/v1/agents/property-maintenance/card", adminToken);
        assertTrue(adminCard.contains("property.ticket.query"), adminCard);
        assertTrue(adminCard.contains("dialect-adapter"), adminCard);

        String userToken = login("user", "user123");
        String userCard = getWithToken("/api/v1/agents/property-maintenance/card", userToken);
        assertTrue(userCard.contains("property.ticket.query"), userCard);

        // 合同 Agent 对普通用户不可见 -> 404
        HttpHeaders userHeaders = new HttpHeaders();
        userHeaders.setBearerAuth(userToken);
        ResponseEntity<String> forbidden = rest.exchange(
                url("/api/v1/agents/contract-review/card"), HttpMethod.GET,
                new HttpEntity<>(userHeaders), String.class);
        assertEquals(HttpStatus.NOT_FOUND, forbidden.getStatusCode());

        // 管理端 A2A 总览
        String summary = getWithToken("/api/v1/admin/a2a/summary", adminToken);
        assertTrue(summary.contains("\"compatibility\""), summary);
        assertTrue(summary.contains("\"agents\""), summary);
        assertTrue(summary.contains("\"health\""), summary);
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

    private String login(String username, String password) throws Exception {
        ResponseEntity<String> response = rest.postForEntity(url("/api/v1/auth/login"),
                jsonEntity(Map.of("username", username, "password", password)), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
        return mapper.readTree(response.getBody()).path("data").path("accessToken").asText();
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
        headers.setBearerAuth(adminToken);
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
            ResponseEntity<String> response = rest.exchange(url("/api/v1/tasks/" + taskNo),
                    HttpMethod.GET, new HttpEntity<>(authHeaders()), String.class);
            assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
            last = mapper.readTree(response.getBody()).path("data");
            if (status.equals(last.path("status").asText())) {
                return last;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("任务未在 " + timeoutMs + "ms 内进入 " + status + "，最后状态：" + last);
    }

    private String getWithToken(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<String> response = rest.exchange(url(path), HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
        return response.getBody();
    }

    private String extractTaskNo(String sse) {
        Matcher matcher = TASK_NO.matcher(sse);
        assertTrue(matcher.find(), "SSE 中没有 taskNo：" + sse);
        return matcher.group(1);
    }

    private HttpEntity<String> jsonEntity(Object body) throws Exception {
        HttpHeaders headers = authHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(mapper.writeValueAsString(body), headers);
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        if (adminToken != null) {
            headers.setBearerAuth(adminToken);
        }
        return headers;
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }
}
