package com.wuye.agent.profile;

import com.wuye.agent.agent.adapter.AdapterRegistry;
import com.wuye.agent.agent.adapter.AgentAdapter;
import com.wuye.agent.agent.install.DynamicAgentRegistry;
import com.wuye.agent.agent.model.AgentDescriptor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

/**
 * H2：聚合多个 Agent 的 {@code forms.personalInfo}。
 *
 * <p>物业 Agent 继续用现有 Manifest 服务（含 mock/fallback）；
 * 运行时安装的 HTTP Agent 通过 {@link AgentAdapter#manifest()} 拉取。
 * 单个 Agent 不可达时只影响自己那一段。</p>
 */
@Service
public class AgentManifestService {

    private static final String PROPERTY_AGENT = "property-maintenance";
    private static final long TTL_MS = 5 * 60_000L;

    private final AdapterRegistry registry;
    private final DynamicAgentRegistry dynamicRegistry;
    private final PropertyManifestService propertyManifestService;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public AgentManifestService(AdapterRegistry registry,
                                DynamicAgentRegistry dynamicRegistry,
                                PropertyManifestService propertyManifestService) {
        this.registry = registry;
        this.dynamicRegistry = dynamicRegistry;
        this.propertyManifestService = propertyManifestService;
    }

    /** 全部资料段：物业 + 运行时安装且声明 personalInfo 的 Agent。 */
    public List<Map<String, Object>> sections() {
        List<Map<String, Object>> result = new ArrayList<>();
        result.add(propertySection());
        for (String agentKey : dynamicRegistry.agentKeys()) {
            if (PROPERTY_AGENT.equals(agentKey)) {
                continue;
            }
            AgentAdapter adapter = registry.get(agentKey);
            if (adapter == null) {
                continue;
            }
            Map<String, Object> section = sectionFor(adapter);
            if (section != null) {
                result.add(section);
            }
        }
        return result;
    }

    public Optional<Map<String, Object>> section(String agentKey) {
        if (PROPERTY_AGENT.equals(agentKey)) {
            return Optional.of(propertySection());
        }
        AgentAdapter adapter = registry.get(agentKey);
        if (adapter == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(sectionFor(adapter));
    }

    private Map<String, Object> propertySection() {
        Map<String, Object> form = propertyManifestService.personalInfoForm();
        return buildSection(PROPERTY_AGENT, "物业 Agent", form,
                "property.profile.get", "property.profile.update", "manifest");
    }

    private Map<String, Object> sectionFor(AgentAdapter adapter) {
        String agentKey = adapter.descriptor().agentKey();
        long now = System.currentTimeMillis();
        CacheEntry cached = cache.get(agentKey);
        if (cached != null && cached.expiresAt() > now) {
            return cached.section();
        }
        Map<String, Object> section = extract(adapter.descriptor(), adapter.manifest());
        if (section == null) {
            cache.remove(agentKey);
            return null;
        }
        cache.put(agentKey, new CacheEntry(section, now + TTL_MS));
        return section;
    }

    private Map<String, Object> extract(AgentDescriptor descriptor, Map<String, Object> manifest) {
        if (manifest == null) {
            return null;
        }
        Object forms = manifest.get("forms");
        if (!(forms instanceof Map<?, ?> formsMap)) {
            return null;
        }
        Object personal = formsMap.get("personalInfo");
        if (!(personal instanceof Map<?, ?> personalMap)) {
            return null;
        }
        Map<String, Object> form = new LinkedHashMap<>();
        personalMap.forEach((key, value) -> form.put(String.valueOf(key), value));
        return buildSection(
                descriptor.agentKey(),
                descriptor.displayName() == null ? descriptor.agentKey() : descriptor.displayName(),
                form,
                text(form.get("getCapability")),
                text(form.get("updateCapability")),
                "manifest");
    }

    private Map<String, Object> buildSection(String agentKey, String displayName,
                                             Map<String, Object> form,
                                             String getCapability, String updateCapability,
                                             String source) {
        String title = text(form.get("title"));
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("agentKey", agentKey);
        section.put("displayName", displayName);
        section.put("title", title == null ? displayName + " 资料" : title);
        section.put("source", text(form.get("source")) == null ? source : text(form.get("source")));
        section.put("form", form);
        section.put("getCapability", getCapability);
        section.put("updateCapability", updateCapability);
        section.put("available", true);
        return section;
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private record CacheEntry(Map<String, Object> section, long expiresAt) {
    }
}
