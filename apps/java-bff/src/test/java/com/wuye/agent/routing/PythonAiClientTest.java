package com.wuye.agent.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.wuye.agent.agent.adapter.AdapterRegistry;
import com.wuye.agent.agent.adapter.MockContractAdapter;
import com.wuye.agent.agent.adapter.MockPropertyAdapter;
import com.wuye.agent.agent.catalog.CapabilitySchemaService;
import com.wuye.agent.agent.catalog.OptionCatalogService;
import com.wuye.agent.agent.http.AgentHttpProperties;
import com.wuye.agent.agent.http.HttpAgentSupport;
import com.wuye.agent.common.Ids;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PythonAiClientTest {

    private HttpServer server;
    private int port;
    private final AtomicReference<String> seenToken = new AtomicReference<>();
    private PythonAiClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/internal/v1/ai/route", this::handle);
        server.createContext("/internal/v1/ai/repair", this::handleRepair);
        server.createContext("/internal/v1/ai/explain", this::handleExplain);
        server.start();

        AiProperties properties = new AiProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://127.0.0.1:" + port);
        properties.setInternalToken("j2p-token");
        properties.setTimeoutMs(2000);

        AdapterRegistry registry = new AdapterRegistry(List.of(
                new MockPropertyAdapter(), new MockContractAdapter()));
        ObjectMapper mapper = new ObjectMapper();
        AgentHttpProperties agentProperties = new AgentHttpProperties();
        HttpAgentSupport agentSupport = new HttpAgentSupport(agentProperties, mapper);
        CapabilitySchemaService schemaService = new CapabilitySchemaService(
                agentProperties, agentSupport, mapper);
        OptionCatalogService optionCatalogService = new OptionCatalogService(
                agentProperties, agentSupport, mapper, new Ids());
        client = new PythonAiClient(properties, registry, schemaService,
                optionCatalogService, mapper);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void parsesLlmDecisionAndSendsInternalToken() {
        Optional<RouteDecision> decision = client.route("查一下知识库");

        assertTrue(decision.isPresent());
        assertEquals("property.ticket.query", decision.get().capabilityId());
        assertEquals("LLM", decision.get().router());
        assertEquals(0.92, decision.get().confidence(), 0.001);
        assertEquals("j2p-token", seenToken.get());
        assertFalse(decision.get().needClarification());
    }

    @Test
    void repairReturnsCorrectedArgs() {
        var repaired = client.repair("创建工单：3栋2单元水管漏水",
                "property.ticket.create", Map.of("description", "水管漏水"),
                "60001", "ticketTypeCode不能为空");
        assertTrue(repaired.isPresent());
        assertEquals("PLUMBING", repaired.get().args().get("ticketTypeCode"));
    }

    @Test
    void explainReturnsNaturalLanguageWithoutChangingOutput() {
        var explanation = client.explain("我有哪些工单？", "property.ticket.query",
                java.util.List.of(Map.of("ticketNo", "GD1")));
        assertTrue(explanation.isPresent());
        assertTrue(explanation.get().contains("1 条"));
    }

    private void handleExplain(HttpExchange exchange) throws IOException {
        byte[] bytes = "{\"explanation\":\"子 Agent 返回 1 条工单记录，原始数据见下方。\"}"
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private void handleRepair(HttpExchange exchange) throws IOException {
        String body = """
                {"capabilityId":"property.ticket.create",
                 "args":{"ticketTypeCode":"PLUMBING","description":"水管漏水"},
                 "needClarification":false,"reason":"补充必填字段"}
                """;
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        seenToken.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
        String body = """
                {"capabilityId":"property.ticket.query","intent":"ticket.query",
                 "args":{"status":"PENDING"},"confidence":0.92,
                 "needClarification":false,"reason":"query","usage":{"totalTokens":12}}
                """;
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
