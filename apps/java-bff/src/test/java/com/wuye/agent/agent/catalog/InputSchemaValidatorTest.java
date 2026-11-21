package com.wuye.agent.agent.catalog;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuye.agent.agent.http.AgentHttpProperties;
import com.wuye.agent.agent.http.HttpAgentSupport;
import java.util.Map;

import org.junit.jupiter.api.Test;

class InputSchemaValidatorTest {

    private InputSchemaValidator validator() {
        AgentHttpProperties properties = new AgentHttpProperties();
        properties.setMode("mock");
        ObjectMapper mapper = new ObjectMapper();
        CapabilitySchemaService schemaService = new CapabilitySchemaService(
                properties, new HttpAgentSupport(properties, mapper), mapper);
        return new InputSchemaValidator(schemaService);
    }

    @Test
    void missingRequiredFieldIsRejected() {
        var result = validator().validate("property.ticket.create",
                Map.of("description", "水管漏水"));

        assertFalse(result.valid());
        assertTrue(result.fields().contains("ticketTypeCode"), result.fields().toString());
    }

    @Test
    void completeArgsPass() {
        var result = validator().validate("property.ticket.create",
                Map.of("ticketTypeCode", "PLUMBING", "description", "水管漏水"));

        assertTrue(result.valid(), result.message());
    }

    @Test
    void wrongTypeIsRejected() {
        var result = validator().validate("property.profile.update",
                Map.of("contactName", "张三", "projectId", "not-a-number"));

        assertFalse(result.valid());
        assertTrue(result.fields().contains("projectId"), result.fields().toString());
    }
}
