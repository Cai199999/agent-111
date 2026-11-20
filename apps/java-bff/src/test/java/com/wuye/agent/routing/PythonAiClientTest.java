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
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
        server.start();

        AiProperties properties = new AiProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://127.0.0.1:" + port);
        properties.setInternalToken("j2p-token");
        properties.setTimeoutMs(2000);

        AdapterRegistry registry = new AdapterRegistry(List.of(
                new MockPropertyAdapter(), new MockContractAdapter()));
        client = new PythonAiClient(properties, registry, new ObjectMapper());
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
