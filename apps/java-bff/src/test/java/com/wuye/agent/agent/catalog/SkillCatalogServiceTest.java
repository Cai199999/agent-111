package com.wuye.agent.agent.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wuye.agent.agent.adapter.AdapterRegistry;
import com.wuye.agent.agent.adapter.AgentAdapter;
import com.wuye.agent.agent.model.AdapterResult;
import com.wuye.agent.agent.model.AdapterTaskRequest;
import com.wuye.agent.agent.model.AgentDescriptor;
import com.wuye.agent.agent.model.AgentHealth;
import com.wuye.agent.agent.model.Capability;
import com.wuye.agent.agent.model.Decision;
import com.wuye.agent.agent.model.TaskEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Manifest Skill：解析、版本刷新、能力选择、目录同步。
 */
class SkillCatalogServiceTest {

    @Test
    void parsesSkillsAndCapabilitiesAndResolvesSkill() {
        FakeAdapter adapter = new FakeAdapter(manifest("0.11.0", "skill.create"));
        AdapterRegistry registry = new AdapterRegistry(List.of(adapter));
        SkillCatalogService catalog = new SkillCatalogService(registry);

        assertEquals("0.11.0", catalog.version("fake-agent"));
        assertEquals(1, catalog.skills("fake-agent").size());
        assertEquals("skill.create", catalog.skills("fake-agent").get(0).skillId());
        assertEquals(1, catalog.capabilities("fake-agent").size());
        assertFalse(catalog.capabilities("fake-agent").get(0).requiresConfirmation());
        assertEquals("skill.create",
                catalog.resolveForCapability("fake.ticket.create", null));
        assertEquals("skill.create",
                catalog.resolveForCapability("fake.ticket.create", "unknown-skill"));
    }

    @Test
    void refreshDetectsNewManifestVersionAndSkill() {
        FakeAdapter adapter = new FakeAdapter(manifest("0.11.0", "skill.create"));
        AdapterRegistry registry = new AdapterRegistry(List.of(adapter));
        SkillCatalogService catalog = new SkillCatalogService(registry);

        assertEquals("skill.create", catalog.resolveForCapability("fake.ticket.create", null));

        adapter.manifest = manifest("0.12.0", "skill.create-v2");
        assertEquals("0.11.0", catalog.version("fake-agent"));

        SkillCatalogService.Snapshot refreshed = catalog.refresh("fake-agent");

        assertEquals("0.12.0", refreshed.version());
        assertEquals("skill.create-v2", refreshed.skills().get(0).skillId());
        assertEquals("skill.create-v2",
                catalog.resolveForCapability("fake.ticket.create", null));
    }

    @Test
    void manifestSyncReplacesCapabilitiesWithoutTouchingAdapter() {
        FakeAdapter adapter = new FakeAdapter(manifest("0.11.0", "skill.create"));
        AdapterRegistry registry = new AdapterRegistry(List.of(adapter));
        SkillCatalogService catalog = new SkillCatalogService(registry);
        ManifestSyncService sync = new ManifestSyncService(registry, catalog,
                new com.wuye.agent.agent.install.DynamicAgentRegistry());

        Map<String, Object> result = sync.sync("fake-agent");

        assertEquals("0.11.0", result.get("version"));
        assertEquals(1, result.get("capabilities"));
        assertTrue(registry.capability("fake.ticket.create").isPresent());
        assertTrue(registry.capability("fake.ticket.create").get().readOnly() == false);
    }

    private static Map<String, Object> manifest(String version, String skillId) {
        Map<String, Object> capability = new LinkedHashMap<>();
        capability.put("capabilityId", "fake.ticket.create");
        capability.put("taskType", "ticket.create");
        capability.put("displayName", "创建工单");
        capability.put("readOnly", false);
        capability.put("requiresConfirmation", false);
        capability.put("executionModes", List.of("SYNC"));

        Map<String, Object> skill = new LinkedHashMap<>();
        skill.put("skillId", skillId);
        skill.put("displayName", "创建工单 Skill");
        skill.put("taskTypes", List.of("ticket.create"));
        skill.put("capabilities", List.of("fake.ticket.create"));
        skill.put("partial", false);
        skill.put("requiresConfirmation", false);

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("version", version);
        manifest.put("capabilities", List.of(capability));
        manifest.put("skills", List.of(skill));
        return manifest;
    }

    private static final class FakeAdapter implements AgentAdapter {
        private final Capability capability = new Capability(
                "fake.ticket.create", "fake-agent", "ticket.create", "fake",
                "创建工单", "创建工单", false, true, List.of("SYNC"), true);
        private final AgentDescriptor descriptor = new AgentDescriptor(
                "fake-agent", "Fake Agent", "fake-v1", "0.11.0", "wrapped",
                "/health", "/ready", true, "db-recovery", List.of(capability));
        private volatile Map<String, Object> manifest;

        private FakeAdapter(Map<String, Object> manifest) {
            this.manifest = manifest;
        }

        @Override
        public AgentDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public boolean supportsManifest() {
            return true;
        }

        @Override
        public Map<String, Object> manifest() {
            return manifest;
        }

        @Override
        public AdapterResult submit(AdapterTaskRequest request) {
            return null;
        }

        @Override
        public AdapterResult getTask(String childTaskId) {
            return null;
        }

        @Override
        public AdapterResult confirm(String childTaskId, String token, Decision decision,
                                     String comment) {
            return null;
        }

        @Override
        public AdapterResult cancel(String childTaskId, String reason) {
            return null;
        }

        @Override
        public List<TaskEvent> events(String childTaskId, long afterSeq) {
            return List.of();
        }

        @Override
        public AgentHealth health() {
            return new AgentHealth("fake-agent", "UP", "UP", 1);
        }
    }
}
