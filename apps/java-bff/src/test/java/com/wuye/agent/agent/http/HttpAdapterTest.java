package com.wuye.agent.agent.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.wuye.agent.agent.model.AdapterResult;
import com.wuye.agent.agent.model.AdapterTaskRequest;
import com.wuye.agent.agent.model.Decision;
import com.wuye.agent.agent.model.TaskStatus;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 真实 HTTP Adapter 契约测试：用 JDK HttpServer 模拟物业 wrapped / 合同 raw 两种方言。
 */
class HttpAdapterTest {

    private HttpServer server;
    private int port;
    private final AtomicReference<String> lastDelegation = new AtomicReference<>();
    private final AtomicBoolean delegationRequestHadDelegation = new AtomicBoolean(false);

    private AgentHttpProperties properties;
    private HttpAgentSupport support;
    private HttpPropertyAdapter propertyAdapter;
    private HttpContractAdapter contractAdapter;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/property", this::handleProperty);
        server.createContext("/contract", this::handleContract);
        server.start();

        properties = new AgentHttpProperties();
        properties.setConnectTimeoutMs(1000);
        properties.setReadTimeoutMs(3000);

        AgentHttpProperties.Endpoint property = properties.getProperty();
        property.setBaseUrl("http://127.0.0.1:" + port + "/property");
        property.setInternalToken("p-token");
        property.setAudience("property-maintenance");
        property.setActorId("10001");
        property.setRoles("ADMIN");

        AgentHttpProperties.Endpoint contract = properties.getContract();
        contract.setBaseUrl("http://127.0.0.1:" + port + "/contract");
        contract.setInternalToken("c-token");
        contract.setAudience("agent-hetong");
        contract.setActorId("10001");
        contract.setRoles("ADMIN");

        support = new HttpAgentSupport(properties, new ObjectMapper());
        propertyAdapter = new HttpPropertyAdapter(support, properties);
        contractAdapter = new HttpContractAdapter(support, properties);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void propertyWrappedEnvelopeAndConfirmationAreParsed() {
        AdapterResult result = propertyAdapter.submit(request("property.ticket.assign", "ticket.assign"));

        assertEquals(TaskStatus.WAITING_CONFIRM, result.status());
        assertEquals("tsk_p_1", result.childTaskId());
        assertNotNull(result.confirmation());
        assertEquals("tok-property", result.confirmation().token());
        assertEquals("派单确认", result.confirmation().title());
        assertEquals("HIGH", result.confirmation().riskLevel());
        assertEquals("dt-property", lastDelegation.get());
        assertFalse(delegationRequestHadDelegation.get(), "签发委托令牌的请求不应携带委托令牌");
    }

    @Test
    void propertyConfirmAndEventsWork() {
        AdapterResult confirmed = propertyAdapter.confirm("tsk_p_1", "tok-property", Decision.APPROVE, "ok");
        assertEquals(TaskStatus.SUCCEEDED, confirmed.status());
        assertEquals(1, confirmed.events().size());
        assertEquals("STATUS_CHANGED", confirmed.events().get(0).type());
    }

    @Test
    void contractRawEnvelopeAsyncAndEventsWork() {
        AdapterResult submitted = contractAdapter.submit(request("contract.review", "contract.review"));
        assertEquals(TaskStatus.PENDING, submitted.status());
        assertEquals("tsk_c_1", submitted.childTaskId());
        assertEquals("dt-contract", lastDelegation.get());

        AdapterResult task = contractAdapter.getTask("tsk_c_1");
        assertEquals(TaskStatus.SUCCEEDED, task.status());
        assertEquals(2, task.events().size());
        assertEquals("RUNNING", task.events().get(0).status().name());
    }

    @Test
    void propertyNumericErrorIsMapped() {
        AdapterResult result = propertyAdapter.submit(request("property.ticket.query.bad", "bad"));
        assertEquals(TaskStatus.FAILED, result.status());
        assertEquals("60001", result.error().code());
        assertEquals("VALIDATION", result.error().category());
        assertFalse(result.error().retryable());
    }

    @Test
    void contractStringErrorIsMapped() {
        AdapterResult result = contractAdapter.submit(request("contract.review.bad", "bad"));
        assertEquals(TaskStatus.FAILED, result.status());
        assertEquals("AUTH_FORBIDDEN", result.error().code());
        assertEquals("AUTH", result.error().category());
    }

    private AdapterTaskRequest request(String capabilityId, String taskType) {
        return new AdapterTaskRequest(capabilityId, taskType, Map.of("ticketNo", "GD1"),
                "client-1", "SYNC", "10001", "trc-1", "trc-1");
    }

    private void handleProperty(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath().substring("/property".length());
        String body = readBody(exchange);
        if (path.equals("/agent/v1/delegation/token")) {
            delegationRequestHadDelegation.set(exchange.getRequestHeaders().containsKey("X-Delegation-Token"));
            json(exchange, 200, "{\"code\":0,\"data\":{\"token\":\"dt-property\",\"expiresInSeconds\":600}}");
            return;
        }
        lastDelegation.set(exchange.getRequestHeaders().getFirst("X-Delegation-Token"));
        if (path.equals("/agent/v1/tasks") && "POST".equals(exchange.getRequestMethod())) {
            if (body.contains("bad")) {
                json(exchange, 400, "{\"code\":60001,\"message\":\"参数错误\"}");
                return;
            }
            json(exchange, 200, """
                    {"code":0,"data":{"taskId":"tsk_p_1","status":"WAITING_CONFIRM",
                    "confirmation":{"confirmationToken":"tok-property","title":"派单确认","riskLevel":"HIGH",
                    "fields":[{"label":"工单","value":"GD1"}],"expiresAt":"2030-01-01T00:00:00Z"}}}
                    """);
            return;
        }
        if (path.endsWith("/confirm")) {
            json(exchange, 200, """
                    {"code":0,"data":{"taskId":"tsk_p_1","status":"SUCCEEDED",
                    "events":[{"seq":1,"type":"STATUS_CHANGED","status":"SUCCEEDED","payload":{"message":"ok"}}]}}
                    """);
            return;
        }
        if (path.contains("/tasks/tsk_p_1/events")) {
            json(exchange, 200, """
                    {"code":0,"data":[{"seq":1,"type":"STATUS_CHANGED","status":"SUCCEEDED","payload":{"message":"ok"}}]}
                    """);
            return;
        }
        if (path.startsWith("/agent/v1/health") || path.startsWith("/agent/v1/ready")) {
            json(exchange, 200, "{\"status\":\"UP\"}");
            return;
        }
        json(exchange, 404, "{\"code\":20001,\"message\":\"not found\"}");
    }

    private void handleContract(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath().substring("/contract".length());
        String body = readBody(exchange);
        if (path.equals("/agent/v1/delegation/token")) {
            delegationRequestHadDelegation.set(exchange.getRequestHeaders().containsKey("X-Delegation-Token"));
            json(exchange, 200, "{\"token\":\"dt-contract\",\"expiresInSeconds\":600}");
            return;
        }
        lastDelegation.set(exchange.getRequestHeaders().getFirst("X-Delegation-Token"));
        if (path.equals("/agent/v1/tasks") && "POST".equals(exchange.getRequestMethod())) {
            if (body.contains("bad")) {
                json(exchange, 403, "{\"code\":\"AUTH_FORBIDDEN\",\"message\":\"forbidden\"}");
                return;
            }
            json(exchange, 202, "{\"taskId\":\"tsk_c_1\",\"status\":\"PENDING\",\"progress\":0}");
            return;
        }
        if (path.equals("/agent/v1/tasks/tsk_c_1")) {
            json(exchange, 200, """
                    {"taskId":"tsk_c_1","status":"SUCCEEDED","output":{"findingsCount":3},
                    "events":[
                      {"seq":1,"eventType":"STATUS_CHANGED","status":"RUNNING","payload":{"message":"running"}},
                      {"seq":2,"eventType":"DONE","status":"SUCCEEDED","payload":{}}
                    ]}
                    """);
            return;
        }
        if (path.startsWith("/health") || path.startsWith("/ready")) {
            json(exchange, 200, "{\"status\":\"UP\"}");
            return;
        }
        json(exchange, 404, "{\"code\":\"TASK_NOT_FOUND\",\"message\":\"not found\"}");
    }

    private String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
