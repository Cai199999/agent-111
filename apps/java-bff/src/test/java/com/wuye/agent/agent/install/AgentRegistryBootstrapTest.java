package com.wuye.agent.agent.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.wuye.agent.agent.adapter.AdapterRegistry;
import com.wuye.agent.agent.http.AgentHttpProperties;
import com.wuye.agent.agent.http.HttpAgentSupport;
import com.wuye.agent.agent.model.AgentDescriptor;
import com.wuye.agent.agent.model.Capability;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentRegistryBootstrapTest {

    private HttpServer server;
    private ObjectMapper mapper;
    private AgentHttpProperties properties;
    private HttpAgentSupport support;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> json(exchange, 200, "{\"status\":\"UP\"}"));
        server.createContext("/ready", exchange -> json(exchange, 200, "{\"status\":\"UP\"}"));
        server.start();

        mapper = new ObjectMapper();
        properties = new AgentHttpProperties();
        support = new HttpAgentSupport(properties, mapper);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void restoresHttpAgentFromRegistry() throws Exception {
        InMemoryAgentRegistryStore store = new InMemoryAgentRegistryStore();
        AdapterRegistry registry = new AdapterRegistry(List.of());
        DynamicAgentRegistry dynamicRegistry = new DynamicAgentRegistry();
        AgentInstallService installService = new AgentInstallService(
                registry, dynamicRegistry, support, properties, mapper, store);

        AgentDescriptor descriptor = descriptor();
        String json = mapper.writeValueAsString(descriptor);
        store.save(entry(json));

        AgentRegistryBootstrap bootstrap = new AgentRegistryBootstrap(
                store, registry, dynamicRegistry, support, mapper, installService, true);
        bootstrap.onReady();

        assertNotNull(registry.get("restored-agent"));
        assertTrue(registry.capability("restored.demo.query").isPresent());
        assertTrue(dynamicRegistry.isDynamic("restored-agent"));
        assertEquals("恢复演示", registry.get("restored-agent").descriptor().displayName());
    }

    @Test
    void skipsWhenLoadOnStartupDisabled() throws Exception {
        InMemoryAgentRegistryStore store = new InMemoryAgentRegistryStore();
        AdapterRegistry registry = new AdapterRegistry(List.of());
        DynamicAgentRegistry dynamicRegistry = new DynamicAgentRegistry();
        AgentInstallService installService = new AgentInstallService(
                registry, dynamicRegistry, support, properties, mapper, store);
        store.save(entry(mapper.writeValueAsString(descriptor())));

        AgentRegistryBootstrap bootstrap = new AgentRegistryBootstrap(
                store, registry, dynamicRegistry, support, mapper, installService, false);
        bootstrap.onReady();

        assertNull(registry.get("restored-agent"));
    }

    private AgentDescriptor descriptor() {
        return new AgentDescriptor(
                "restored-agent",
                "恢复演示",
                "canonical-v1",
                "0.1.0",
                "raw",
                "/health",
                "/ready",
                true,
                "none",
                List.of(new Capability(
                        "restored.demo.query",
                        "restored-agent",
                        "query",
                        "restored",
                        "查询",
                        "查询",
                        true,
                        false,
                        List.of("SYNC"),
                        true)));
    }

    private AgentRegistryEntry entry(String descriptorJson) {
        Instant now = Instant.now();
        return new AgentRegistryEntry(
                "restored-agent",
                "恢复演示",
                "0.1.0",
                "canonical-v1",
                AgentRegistryEntry.KIND_HTTP,
                null,
                descriptorJson,
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "test-token",
                "restored-agent",
                "10001",
                "ADMIN",
                "0",
                false,
                "/health",
                "/ready",
                "/agent/v1/manifest",
                AgentRegistryEntry.STATUS_ACTIVE,
                "test",
                now,
                now);
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
