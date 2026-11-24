package com.wuye.agent.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuye.agent.common.ApiException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class DynamicPermissionParseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesCapabilitiesAndDerivesAgentUseRoles() throws Exception {
        JsonNode node = mapper.readTree("""
                {
                  "agentUse": "agent:oa-entry-agent:use",
                  "capabilities": {
                    "oa.request.draft": ["BASE", "WORKER"],
                    "oa.workflow.act": ["WORKER", "ADMIN"]
                  }
                }
                """);

        Map<String, MySqlDynamicPermissionService.PermissionPlan> plans =
                MySqlDynamicPermissionService.parse("oa-entry-agent", node);

        assertEquals(3, plans.size());
        assertTrue(plans.containsKey("capability:oa.request.draft"));
        assertTrue(plans.containsKey("capability:oa.workflow.act"));
        assertEquals(List.of("BASE", "WORKER", "ADMIN"),
                plans.get("agent:oa-entry-agent:use").roles());
    }

    @Test
    void agentUseRolesOverrideUnion() throws Exception {
        JsonNode node = mapper.readTree("""
                {
                  "agentUse": "agent:oa-entry-agent:use",
                  "agentUseRoles": ["ADMIN"],
                  "capabilities": {"oa.request.draft": ["BASE"]}
                }
                """);

        Map<String, MySqlDynamicPermissionService.PermissionPlan> plans =
                MySqlDynamicPermissionService.parse("oa-entry-agent", node);

        assertEquals(List.of("ADMIN"), plans.get("agent:oa-entry-agent:use").roles());
    }

    @Test
    void missingPermissionsIsEmpty() {
        assertTrue(MySqlDynamicPermissionService.parse("oa-entry-agent", null).isEmpty());
        assertTrue(MySqlDynamicPermissionService.parse("oa-entry-agent",
                mapper.missingNode()).isEmpty());
    }

    @Test
    void invalidAgentUseRejected() throws Exception {
        JsonNode node = mapper.readTree("""
                {"agentUse": "not-an-agent-permission"}
                """);
        assertThrows(ApiException.class,
                () -> MySqlDynamicPermissionService.parse("oa-entry-agent", node));
    }

    @Test
    void emptyCapabilityRolesAreSkipped() throws Exception {
        JsonNode node = mapper.readTree("""
                {"capabilities": {"oa.request.draft": [], "oa.request.query": ["BASE"]}}
                """);
        Map<String, MySqlDynamicPermissionService.PermissionPlan> plans =
                MySqlDynamicPermissionService.parse("oa-entry-agent", node);
        assertFalse(plans.containsKey("capability:oa.request.draft"));
        assertTrue(plans.containsKey("capability:oa.request.query"));
    }
}
