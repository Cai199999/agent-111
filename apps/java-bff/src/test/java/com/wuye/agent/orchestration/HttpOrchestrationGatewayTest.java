package com.wuye.agent.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.wuye.agent.agent.model.Capability;
import com.wuye.agent.routing.AiProperties;
import com.wuye.agent.routing.RouteDecision;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 编排 HTTP 网关：请求体/令牌、计划解析、验收解析、失败回退。
 */
class HttpOrchestrationGatewayTest {

    private HttpServer server;
    private AiProperties properties;
    private final AtomicReference<String> planBody = new AtomicReference<>();
    private final AtomicReference<String> planToken = new AtomicReference<>();
    private final AtomicReference<String> verifyBody = new AtomicReference<>();
    private volatile int planStatus = 200;
    private volatile int verifyStatus = 200;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/ai/orchestrate", exchange -> {
            planBody.set(read(exchange));
            planToken.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            respond(exchange, planStatus, """
                    {"verdict":"DISPATCH_REQUIRED","plan":[
                      {"capabilityId":"oa.request.draft","args":{"days":2}}
                    ],"steps":[],"notes":["steps=1"]}
                    """);
        });
        // 更长的路径必须先注册，否则会被 /orchestrate 前缀匹配吞掉。
        HttpServer verifyServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        verifyServer.createContext("/internal/v1/ai/orchestrate/verify", exchange -> {
            verifyBody.set(read(exchange));
            respond(exchange, verifyStatus, """
                    {"verdict":"NEEDS_INPUT","clarification":"还需要补充：startDate",
                     "notes":["steps=1"]}
                    """);
        });
        verifyServer.start();
        this.verifyServer = verifyServer;
        server.start();

        properties = new AiProperties();
        properties.setEnabled(true);
        properties.setOrchestrationEnabled(true);
        properties.setInternalToken("test-token");
        properties.setTimeoutMs(2000);
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private HttpServer verifyServer;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        if (verifyServer != null) {
            verifyServer.stop(0);
        }
    }

    @Test
    void planSendsCapabilitiesPlanAndToken() {
        HttpOrchestrationGateway gateway = new HttpOrchestrationGateway(
                properties, new ObjectMapper());
        Capability capability = new Capability("oa.request.draft", "oa-agent",
                "request.draft", "oa", "请假申请", "请假申请",
                false, true, List.of("SYNC"), true);

        Optional<OrchestrationResult> result = gateway.plan(new OrchestrationGateway.PlanRequest(
                "下周三请两天年假", "trc_1", "ws_1", "10001", "0",
                List.of(capability),
                List.of(new RouteDecision.PlanStep("oa.request.draft", Map.of("days", 2)))));

        assertTrue(result.isPresent());
        assertEquals("DISPATCH_REQUIRED", result.get().verdict());
        assertEquals("oa.request.draft", result.get().plan().get(0).capabilityId());
        assertEquals(2, result.get().plan().get(0).args().get("days"));
        assertEquals("test-token", planToken.get());
        assertTrue(planBody.get().contains("\"dispatchMode\":\"PLAN_ONLY\""), planBody.get());
        assertTrue(planBody.get().contains("\"capabilities\""), planBody.get());
    }

    @Test
    void verifyParsesClarification() {
        AiProperties verifyProperties = new AiProperties();
        verifyProperties.setEnabled(true);
        verifyProperties.setOrchestrationEnabled(true);
        verifyProperties.setInternalToken("test-token");
        verifyProperties.setTimeoutMs(2000);
        verifyProperties.setBaseUrl(
                "http://127.0.0.1:" + verifyServer.getAddress().getPort());
        HttpOrchestrationGateway gateway = new HttpOrchestrationGateway(
                verifyProperties, new ObjectMapper());

        Optional<OrchestrationResult> result = gateway.verify(new OrchestrationGateway.VerifyRequest(
                "trc_1", "ws_1",
                List.of(new RouteDecision.PlanStep("oa.request.draft", Map.of())),
                List.of(new OrchestrationResult.StepResult(
                        "oa.request.draft", "oa-agent", "SUCCEEDED",
                        Map.of("requestNo", "QJ-1"), null, List.of()))));

        assertTrue(result.isPresent());
        assertEquals("NEEDS_INPUT", result.get().verdict());
        assertTrue(result.get().clarification().contains("startDate"));
        assertTrue(verifyBody.get().contains("\"steps\""), verifyBody.get());
    }

    @Test
    void serverErrorFallsBackToEmpty() {
        planStatus = 503;
        HttpOrchestrationGateway gateway = new HttpOrchestrationGateway(
                properties, new ObjectMapper());

        Optional<OrchestrationResult> result = gateway.plan(new OrchestrationGateway.PlanRequest(
                "hello", "trc_1", "ws_1", "10001", "0", List.of(), List.of()));

        assertTrue(result.isEmpty());
    }

    private static String read(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
