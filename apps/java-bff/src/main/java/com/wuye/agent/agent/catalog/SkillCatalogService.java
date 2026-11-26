package com.wuye.agent.agent.catalog;

import com.wuye.agent.agent.adapter.AdapterRegistry;
import com.wuye.agent.agent.adapter.AgentAdapter;
import com.wuye.agent.agent.model.AgentDescriptor;
import com.wuye.agent.agent.model.AgentSkill;
import com.wuye.agent.agent.model.Capability;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Manifest Skill 目录：版本化缓存 + 按能力选择 Skill。
 *
 * <p>Skill 是子 Agent 0.11.0 新增的任务级封装；主 Agent 只做通用解析与选择，
 * 不写死物业/合同的具体 Skill 名称。Manifest 不可达时回退能力路由，不阻塞对话。</p>
 */
@Service
public class SkillCatalogService {

    private static final Logger log = LoggerFactory.getLogger(SkillCatalogService.class);
    private static final long TTL_MS = 5 * 60_000L;

    private final AdapterRegistry registry;
    private final Map<String, Snapshot> cache = new ConcurrentHashMap<>();

    public SkillCatalogService(AdapterRegistry registry) {
        this.registry = registry;
    }

    public Optional<Snapshot> snapshot(String agentKey, boolean force) {
        AgentAdapter adapter = registry.get(agentKey);
        if (adapter == null || !adapter.supportsManifest()) {
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        Snapshot cached = cache.get(agentKey);
        if (!force && cached != null && cached.expiresAt() > now) {
            return Optional.of(cached);
        }
        Map<String, Object> manifest = safeManifest(adapter);
        if (manifest.isEmpty() && cached != null && !force) {
            // 子 Agent 暂不可达时保留旧版本，避免一次网络抖动清空 Skill 目录。
            cache.put(agentKey, cached.withExpiresAt(now + 30_000));
            return Optional.of(cached);
        }
        String version = text(manifest.get("version"), manifest.get("manifestVersion"));
        if (version == null) {
            version = adapter.descriptor().contractVersion();
        }
        List<AgentSkill> skills = parseSkills(agentKey, manifest.get("skills"));
        List<Capability> capabilities = parseCapabilities(agentKey, manifest.get("capabilities"));
        Snapshot snapshot = new Snapshot(agentKey, version, skills, capabilities, now + TTL_MS);
        if (cached != null && !version.equals(cached.version())) {
            log.info("manifest version changed agent={} old={} new={} skills={}",
                    agentKey, cached.version(), version, skills.size());
        }
        cache.put(agentKey, snapshot);
        return Optional.of(snapshot);
    }

    public List<AgentSkill> skills(String agentKey) {
        return snapshot(agentKey, false).map(Snapshot::skills).orElse(List.of());
    }

    /** Manifest 声明的能力；为空表示沿用注册表里的静态能力。 */
    public List<Capability> capabilities(String agentKey) {
        return snapshot(agentKey, false).map(Snapshot::capabilities).orElse(List.of());
    }

    public List<AgentSkill> allSkills() {
        List<AgentSkill> result = new ArrayList<>();
        for (AgentDescriptor descriptor : registry.descriptors()) {
            result.addAll(skills(descriptor.agentKey()));
        }
        return result;
    }

    public String version(String agentKey) {
        return snapshot(agentKey, false)
                .map(Snapshot::version)
                .orElseGet(() -> {
                    AgentAdapter adapter = registry.get(agentKey);
                    return adapter == null ? null : adapter.descriptor().contractVersion();
                });
    }

    public Snapshot refresh(String agentKey) {
        return snapshot(agentKey, true)
                .orElseThrow(() -> new IllegalArgumentException("Agent 未注册：" + agentKey));
    }

    /**
     * 解析最终 skillId：优先采用 LLM 返回且覆盖该能力的 Skill；
     * 否则在覆盖该能力的非 partial Skill 中做确定性选择；多个候选不猜。
     */
    public String resolveForCapability(String capabilityId, String requestedSkillId) {
        Capability capability = registry.capability(capabilityId).orElse(null);
        if (capability == null) {
            return null;
        }
        List<AgentSkill> skills = skills(capability.agentKey());
        if (requestedSkillId != null && !requestedSkillId.isBlank()) {
            Optional<AgentSkill> requested = skills.stream()
                    .filter(skill -> requestedSkillId.equals(skill.skillId()))
                    .findFirst();
            if (requested.isPresent()
                    && requested.get().covers(capability.capabilityId(), capability.taskType())) {
                return requested.get().skillId();
            }
            log.debug("忽略不匹配的 skillId={} capability={}", requestedSkillId, capabilityId);
        }
        List<AgentSkill> candidates = skills.stream()
                .filter(skill -> skill.covers(capability.capabilityId(), capability.taskType()))
                .toList();
        if (candidates.isEmpty()) {
            return null;
        }
        List<AgentSkill> complete = candidates.stream().filter(skill -> !skill.partial()).toList();
        List<AgentSkill> pool = complete.isEmpty() ? candidates : complete;
        List<AgentSkill> exact = pool.stream()
                .filter(skill -> skill.taskTypes().contains(capability.taskType()))
                .toList();
        if (exact.size() == 1) {
            return exact.get(0).skillId();
        }
        return pool.size() == 1 ? pool.get(0).skillId() : null;
    }

    /** Agent Card 使用的 Skill 视图。 */
    public Map<String, Object> view(AgentSkill skill) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("skillId", skill.skillId());
        view.put("name", skill.displayName());
        view.put("description", skill.description());
        view.put("taskTypes", skill.taskTypes());
        view.put("capabilities", skill.capabilityIds());
        view.put("partial", skill.partial());
        view.put("requiresConfirmation", skill.requiresConfirmation());
        return view;
    }

    private Map<String, Object> safeManifest(AgentAdapter adapter) {
        try {
            Map<String, Object> manifest = adapter.manifest();
            return manifest == null ? Map.of() : manifest;
        } catch (Exception ex) {
            log.debug("manifest unavailable agent={} error={}",
                    adapter.descriptor().agentKey(), ex.getMessage());
            return Map.of();
        }
    }

    private List<AgentSkill> parseSkills(String agentKey, Object node) {
        if (!(node instanceof List<?> list)) {
            return List.of();
        }
        List<AgentSkill> skills = new ArrayList<>();
        for (Object raw : list) {
            if (!(raw instanceof Map<?, ?> map)) {
                continue;
            }
            String skillId = text(map.get("skillId"), map.get("id"), map.get("name"));
            if (skillId == null || skillId.isBlank()) {
                continue;
            }
            List<String> taskTypes = strings(map.get("taskTypes"),
                    map.get("taskType"), map.get("tasks"));
            List<String> capabilityIds = strings(map.get("capabilityIds"),
                    map.get("capabilities"), map.get("capabilityIds"));
            if (taskTypes.isEmpty() && capabilityIds.isEmpty()) {
                // 没有覆盖范围无法用于选择，跳过而不是猜。
                continue;
            }
            skills.add(new AgentSkill(
                    agentKey,
                    skillId,
                    text(map.get("displayName"), map.get("name"), map.get("title")),
                    text(map.get("description")),
                    taskTypes,
                    capabilityIds,
                    bool(map.get("partial"), false),
                    bool(map.get("requiresConfirmation"), false)));
        }
        return skills;
    }

    private List<Capability> parseCapabilities(String agentKey, Object node) {
        if (!(node instanceof List<?> list)) {
            return List.of();
        }
        List<Capability> capabilities = new ArrayList<>();
        for (Object raw : list) {
            if (raw instanceof String id && !id.isBlank()) {
                capabilities.add(new Capability(id, agentKey, lastSegment(id), namespace(id),
                        id, id, false, true, List.of("SYNC"), true));
                continue;
            }
            if (!(raw instanceof Map<?, ?> map)) {
                continue;
            }
            String id = text(map.get("capabilityId"), map.get("id"), map.get("name"));
            if (id == null || id.isBlank()) {
                continue;
            }
            boolean readOnly = bool(map.get("readOnly"), false);
            String name = text(map.get("displayName"), map.get("name"), id);
            List<String> modes = strings(map.get("executionModes"), map.get("executionMode"));
            if (modes.isEmpty()) {
                modes = List.of("SYNC");
            }
            capabilities.add(new Capability(
                    id,
                    agentKey,
                    text(map.get("taskType"), lastSegment(id)),
                    namespace(id),
                    name,
                    text(map.get("description"), name),
                    readOnly,
                    // 0.11.0 以 requiresConfirmation 为准；缺省时保持旧行为。
                    bool(map.get("requiresConfirmation"), !readOnly),
                    modes,
                    bool(map.get("enabled"), true)));
        }
        return capabilities;
    }

    private static String lastSegment(String id) {
        int index = id.lastIndexOf('.');
        return index < 0 ? id : id.substring(index + 1);
    }

    private static String namespace(String id) {
        int index = id.indexOf('.');
        return index < 0 ? "agent" : id.substring(0, index);
    }

    private static List<String> strings(Object... values) {
        for (Object value : values) {
            if (value instanceof List<?> list) {
                List<String> result = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        String text = text(map.get("capabilityId"), map.get("id"), map.get("taskType"));
                        if (text != null) {
                            result.add(text);
                        }
                    } else if (item != null) {
                        String text = String.valueOf(item);
                        if (!text.isBlank()) {
                            result.add(text);
                        }
                    }
                }
                return result;
            }
            if (value instanceof String text && !text.isBlank()) {
                return List.of(text);
            }
        }
        return List.of();
    }

    private static String text(Object... values) {
        for (Object value : values) {
            if (value != null) {
                String text = String.valueOf(value);
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return null;
    }

    private static boolean bool(Object value, boolean fallback) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value != null) {
            return Boolean.parseBoolean(String.valueOf(value));
        }
        return fallback;
    }

    public record Snapshot(String agentKey, String version, List<AgentSkill> skills,
                           List<Capability> capabilities, long expiresAt) {
        public Snapshot {
            skills = skills == null ? List.of() : List.copyOf(skills);
            capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        }

        public Snapshot withExpiresAt(long newExpiresAt) {
            return new Snapshot(agentKey, version, skills, capabilities, newExpiresAt);
        }

        public Instant fetchedAt() {
            return Instant.ofEpochMilli(expiresAt - TTL_MS);
        }
    }
}
