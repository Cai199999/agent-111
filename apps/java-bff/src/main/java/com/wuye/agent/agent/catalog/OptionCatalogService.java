package com.wuye.agent.agent.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuye.agent.agent.http.AgentHttpProperties;
import com.wuye.agent.agent.http.HttpAgentSupport;
import com.wuye.agent.common.Ids;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 读取物业 masterdata.options（ticketTypes/priorities/locationTypes 等），
 * 提供给 LLM 做“中文名 → code”的选择，避免向用户抛裸编码。
 */
@Service
public class OptionCatalogService {

    private static final Logger log = LoggerFactory.getLogger(OptionCatalogService.class);

    private final AgentHttpProperties properties;
    private final HttpAgentSupport support;
    private final ObjectMapper mapper;
    private final Ids ids;
    private volatile Map<String, Object> cached;
    private volatile long expiresAt;

    public OptionCatalogService(AgentHttpProperties properties, HttpAgentSupport support,
                                ObjectMapper mapper, Ids ids) {
        this.properties = properties;
        this.support = support;
        this.mapper = mapper;
        this.ids = ids;
    }

    public Map<String, Object> options() {
        long now = System.currentTimeMillis();
        if (cached != null && now < expiresAt) {
            return cached;
        }
        if (!"http".equalsIgnoreCase(properties.getMode())) {
            cached = Map.of();
            expiresAt = now + 30_000;
            return cached;
        }
        try {
            AgentHttpProperties.Endpoint endpoint = properties.getProperty();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("taskType", "masterdata.options");
            body.put("input", Map.of());
            body.put("executionMode", "SYNC");
            body.put("clientTaskId", "options-" + UUID.randomUUID());
            HttpAgentSupport.HttpResult result = support.post(endpoint, "/agent/v1/tasks", body,
                    endpoint.getActorId(), ids.traceId(), false);
            if (result.status() >= 200 && result.status() < 300) {
                JsonNode data = support.unwrap(result.body(), com.wuye.agent.agent.http.AgentDialect.PROPERTY);
                JsonNode output = data.get("output");
                if (output == null || output.isNull()) {
                    output = data.get("result");
                }
                if (output != null && output.isObject()) {
                    Map<String, Object> options = mapper.convertValue(output,
                            new TypeReference<Map<String, Object>>() {
                            });
                    cached = options;
                    expiresAt = now + 5 * 60_000;
                    return options;
                }
            }
            log.warn("读取 masterdata.options 失败 status={}", result.status());
        } catch (Exception ex) {
            log.warn("读取 masterdata.options 异常：{}", ex.getMessage());
        }
        cached = Map.of();
        expiresAt = now + 30_000;
        return cached;
    }

    public List<Map<String, Object>> optionList(String key) {
        Object value = options().get(key);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return mapper.convertValue(list, new TypeReference<List<Map<String, Object>>>() {
        });
    }
}
