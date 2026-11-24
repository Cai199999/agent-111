package com.wuye.agent.agent.install;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuye.agent.agent.adapter.AdapterRegistry;
import com.wuye.agent.agent.http.AgentHttpProperties;
import com.wuye.agent.agent.http.DynamicHttpAgentAdapter;
import com.wuye.agent.agent.http.HttpAgentSupport;
import com.wuye.agent.agent.model.AgentDescriptor;
import com.wuye.agent.agent.model.Capability;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * H1：启动时恢复运行时安装的 Agent。
 *
 * <p>只重建注册关系，不主动探活；健康状态由管理端按需查询，
 * 避免子 Agent 不可达时拖慢底座启动。</p>
 */
@Component
public class AgentRegistryBootstrap {

    private static final Logger log = LoggerFactory.getLogger(AgentRegistryBootstrap.class);

    private final AgentRegistryStore store;
    private final AdapterRegistry registry;
    private final DynamicAgentRegistry dynamicRegistry;
    private final HttpAgentSupport support;
    private final ObjectMapper mapper;
    private final AgentInstallService installService;
    private final boolean loadOnStartup;

    public AgentRegistryBootstrap(AgentRegistryStore store,
                                  AdapterRegistry registry,
                                  DynamicAgentRegistry dynamicRegistry,
                                  HttpAgentSupport support,
                                  ObjectMapper mapper,
                                  AgentInstallService installService,
                                  @Value("${wuye.agent.registry.load-on-startup:true}") boolean loadOnStartup) {
        this.store = store;
        this.registry = registry;
        this.dynamicRegistry = dynamicRegistry;
        this.support = support;
        this.mapper = mapper;
        this.installService = installService;
        this.loadOnStartup = loadOnStartup;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!loadOnStartup) {
            log.info("Agent 注册表启动加载已关闭");
            return;
        }
        List<AgentRegistryEntry> entries = store.listActive();
        if (entries.isEmpty()) {
            return;
        }
        int restored = 0;
        for (AgentRegistryEntry entry : entries) {
            try {
                if (restore(entry)) {
                    restored++;
                }
            } catch (Exception ex) {
                log.warn("恢复 Agent 失败 agent={} error={}", entry.agentKey(), ex.getMessage());
            }
        }
        log.info("Agent 注册表启动加载完成 restored={} total={}", restored, entries.size());
    }

    private boolean restore(AgentRegistryEntry entry) throws Exception {
        if (registry.get(entry.agentKey()) != null) {
            log.info("跳过已存在的 Agent {}", entry.agentKey());
            return false;
        }
        if (AgentRegistryEntry.KIND_TEMPLATE.equals(entry.kind())) {
            if (entry.templateKey() == null || entry.templateKey().isBlank()) {
                log.warn("模板 Agent 缺少 template_key，跳过 agent={}", entry.agentKey());
                return false;
            }
            installService.restoreTemplate(entry.templateKey());
            log.info("恢复模板 Agent {} template={}", entry.agentKey(), entry.templateKey());
            return true;
        }

        if (entry.descriptorJson() == null || entry.descriptorJson().isBlank()) {
            log.warn("HTTP Agent 缺少 descriptor_json，跳过 agent={}", entry.agentKey());
            return false;
        }
        AgentDescriptor descriptor = mapper.readValue(entry.descriptorJson(), AgentDescriptor.class);

        AgentHttpProperties.Endpoint endpoint = new AgentHttpProperties.Endpoint();
        endpoint.setBaseUrl(entry.baseUrl());
        endpoint.setInternalToken(entry.internalToken());
        endpoint.setAudience(entry.audience());
        endpoint.setActorId(entry.actorId());
        endpoint.setRoles(entry.roles());
        endpoint.setTenantId(entry.tenantId());
        endpoint.setUseDelegation(entry.useDelegation());
        endpoint.setManifestPath(entry.manifestPath());

        DynamicHttpAgentAdapter adapter = new DynamicHttpAgentAdapter(support, endpoint, descriptor);
        registry.register(adapter);
        dynamicRegistry.markInstalled(entry.agentKey(), descriptor.capabilities().stream()
                .map(Capability::capabilityId)
                .toList());
        log.info("恢复 HTTP Agent {} baseUrl={}", entry.agentKey(), entry.baseUrl());
        return true;
    }
}
