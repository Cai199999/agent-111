package com.wuye.agent.agent.catalog;

import com.wuye.agent.agent.adapter.AdapterRegistry;
import com.wuye.agent.agent.install.DynamicAgentRegistry;
import com.wuye.agent.agent.model.AgentDescriptor;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Manifest 同步：版本识别 + 能力/ Skill 目录刷新。
 *
 * <p>启动时尝试同步一次；子 Agent 不可达时保留静态目录，不阻塞主流程。
 * 管理端可显式刷新，用于子 Agent 升级后立即生效。</p>
 */
@Service
public class ManifestSyncService {

    private static final Logger log = LoggerFactory.getLogger(ManifestSyncService.class);

    private final AdapterRegistry registry;
    private final SkillCatalogService skillCatalog;
    private final DynamicAgentRegistry dynamicRegistry;

    public ManifestSyncService(AdapterRegistry registry, SkillCatalogService skillCatalog,
                               DynamicAgentRegistry dynamicRegistry) {
        this.registry = registry;
        this.skillCatalog = skillCatalog;
        this.dynamicRegistry = dynamicRegistry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        syncAll();
    }

    public Map<String, Object> syncAll() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (AgentDescriptor descriptor : registry.descriptors()) {
            // 动态安装的 Agent 在安装时已按 Card 建立目录；启动时不再主动探活，
            // 避免子 Agent 不可达时产生无意义的错误日志。管理员仍可显式刷新。
            var adapter = registry.get(descriptor.agentKey());
            if (adapter == null || !adapter.supportsManifest()
                    || dynamicRegistry.isDynamic(descriptor.agentKey())) {
                continue;
            }
            result.put(descriptor.agentKey(), sync(descriptor.agentKey()));
        }
        return result;
    }

    /** 强制拉取 Manifest；成功时替换能力目录，失败时保留旧目录。 */
    public Map<String, Object> sync(String agentKey) {
        var adapter = registry.get(agentKey);
        if (adapter == null || !adapter.supportsManifest()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("agentKey", agentKey);
            result.put("version", adapter == null ? null : adapter.descriptor().contractVersion());
            result.put("capabilities", registry.capabilitiesForAgent(agentKey).size());
            result.put("skills", 0);
            result.put("source", "UNSUPPORTED");
            return result;
        }
        SkillCatalogService.Snapshot snapshot = skillCatalog.refresh(agentKey);
        if (!snapshot.capabilities().isEmpty()) {
            registry.replaceCapabilities(agentKey, snapshot.capabilities());
        }
        log.info("manifest synced agent={} version={} capabilities={} skills={}",
                agentKey, snapshot.version(),
                snapshot.capabilities().size(), snapshot.skills().size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agentKey", agentKey);
        result.put("version", snapshot.version());
        result.put("capabilities", snapshot.capabilities().size());
        result.put("skills", snapshot.skills().size());
        result.put("source", snapshot.capabilities().isEmpty() ? "STATIC" : "MANIFEST");
        return result;
    }

    public Map<String, Object> stats(String agentKey) {
        SkillCatalogService.Snapshot snapshot = skillCatalog.snapshot(agentKey, false)
                .orElse(null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agentKey", agentKey);
        result.put("version", snapshot == null
                ? registry.get(agentKey) == null ? null : registry.get(agentKey).descriptor().contractVersion()
                : snapshot.version());
        result.put("capabilities", registry.capabilitiesForAgent(agentKey).size());
        result.put("skills", snapshot == null ? 0 : snapshot.skills().size());
        return result;
    }
}
