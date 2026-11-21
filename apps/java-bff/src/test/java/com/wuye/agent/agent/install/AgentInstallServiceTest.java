package com.wuye.agent.agent.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.wuye.agent.agent.adapter.AdapterRegistry;
import com.wuye.agent.agent.http.AgentHttpProperties;
import com.wuye.agent.agent.http.HttpAgentSupport;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentInstallServiceTest {

    private HttpServer server;
    private AgentInstallService service;
    private AdapterRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/.well-known/agent-card.json", this::card);
        server.createContext("/health", exchange -> json(exchange, 200, "{\"status\":\"UP\"}"));
        server.createContext("/ready", exchange -> json(exchange, 200, "{\"status\":\"UP\"}"));
        server.start();

        AgentHttpProperties properties = new AgentHttpProperties();
        ObjectMapper mapper = new ObjectMapper();
        registry = new AdapterRegistry(List.of());
        DynamicAgentRegistry dynamicRegistry = new DynamicAgentRegistry();
        HttpAgentSupport support = new HttpAgentSupport(properties, mapper);
        service = new AgentInstallService(registry, dynamicRegistry, support, properties, mapper);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void installsCanonicalHttpAgentFromCardUrl() {
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        Map<String, Object> result = service.installFromCardUrl(url, null);

        assertEquals("external-demo-agent", result.get("agentKey"));
        assertNotNull(registry.get("external-demo-agent"));
        assertTrue(registry.capability("external.demo.query").isPresent());
        assertTrue(registry.capability("external.demo.write").isPresent());

        service.uninstall("external-demo-agent");
        assertTrue(registry.capability("external.demo.query").isEmpty());
    }

    private void card(HttpExchange exchange) throws IOException {
        String body = """
                {
                  "agentId": "external-demo-agent",
                  "name": "外部演示 Agent",
                  "version": "0.2.0",
                  "capabilities": [
                    {"capabilityId":"external.demo.query","name":"查询演示","readOnly":true,
                     "requiresConfirmation":false,"executionModes":["SYNC"]},
                    {"capabilityId":"external.demo.write","name":"写入演示","readOnly":false,
                     "requiresConfirmation":true,"executionModes":["SYNC"]}
                  ],
                  "endpoints": {"health":"/health","ready":"/ready"},
                  "features": {"cancel":true,"asyncDurability":"none"}
                }
                """;
        json(exchange, 200, body);
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
