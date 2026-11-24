package com.wuye.agent.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.wuye.agent.agent.adapter.AdapterRegistry;
import com.wuye.agent.agent.adapter.AgentAdapter;
import com.wuye.agent.agent.install.DynamicAgentRegistry;
import com.wuye.agent.agent.model.AgentDescriptor;
import com.wuye.agent.agent.model.Capability;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentManifestServiceTest {

    private AgentAdapter oa;
    private PropertyManifestService property;
    private DynamicAgentRegistry dynamicRegistry;
    private AgentManifestService service;

    @BeforeEach
    void setUp() {
        oa = mock(AgentAdapter.class);
        when(oa.descriptor()).thenReturn(new AgentDescriptor(
                "oa-entry-agent", "OA 入口 Agent", "canonical-v1", "0.1.0", "raw",
                "/health", "/ready", true, "db",
                List.of(new Capability("oa.profile.bind", "oa-entry-agent",
                        "bind", "oa", "绑定", "绑定", false, true,
                        List.of("SYNC"), true))));
        when(oa.manifest()).thenReturn(Map.of(
                "forms", Map.of("personalInfo", Map.of(
                        "title", "办公身份",
                        "getCapability", "oa.profile.get",
                        "updateCapability", "oa.profile.bind",
                        "fields", List.of(Map.of("name", "employee_no", "label", "工号",
                                "type", "string", "required", true))))));

        property = mock(PropertyManifestService.class);
        when(property.personalInfoForm()).thenReturn(Map.of(
                "fields", List.of(Map.of("name", "contactName", "label", "联系人")),
                "source", "mock-mode"));

        dynamicRegistry = new DynamicAgentRegistry();
        dynamicRegistry.markInstalled("oa-entry-agent", List.of("oa.profile.bind"));

        AdapterRegistry registry = new AdapterRegistry(List.of(oa));
        service = new AgentManifestService(registry, dynamicRegistry, property);
    }

    @Test
    void aggregatesPropertyAndDynamicAgentForms() {
        List<Map<String, Object>> sections = service.sections();

        assertEquals(2, sections.size());
        assertEquals("property-maintenance", sections.get(0).get("agentKey"));
        assertEquals("oa-entry-agent", sections.get(1).get("agentKey"));
        assertEquals("办公身份", sections.get(1).get("title"));
        assertEquals("oa.profile.bind", sections.get(1).get("updateCapability"));
        assertTrue((Boolean) sections.get(1).get("available"));
    }

    @Test
    void sectionByAgentKeyWorksAndCachesManifest() {
        Map<String, Object> section = service.section("oa-entry-agent").orElseThrow();
        assertEquals("OA 入口 Agent", section.get("displayName"));
        service.section("oa-entry-agent");
        verify(oa, times(1)).manifest();
    }

    @Test
    void dynamicAgentWithoutPersonalInfoIsSkipped() {
        when(oa.manifest()).thenReturn(Map.of("forms", Map.of("other", Map.of())));
        List<Map<String, Object>> sections = service.sections();
        assertEquals(1, sections.size());
        assertEquals("property-maintenance", sections.get(0).get("agentKey"));
        assertFalse(service.section("oa-entry-agent").isPresent());
    }
}
