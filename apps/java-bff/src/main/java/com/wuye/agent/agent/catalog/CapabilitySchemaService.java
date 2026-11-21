package com.wuye.agent.agent.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuye.agent.agent.http.AgentHttpProperties;
import com.wuye.agent.agent.http.HttpAgentSupport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 从子 Agent Manifest 读取 capabilities[].inputSchema，供 LLM 判断必填字段/拆步骤。
 *
 * <p>Manifest 不可达时返回最小兜底 schema，不让 LLM 盲猜。</p>
 */
@Service
public class CapabilitySchemaService {

    private static final Logger log = LoggerFactory.getLogger(CapabilitySchemaService.class);

    private final AgentHttpProperties properties;
    private final HttpAgentSupport support;
    private final ObjectMapper mapper;
    private volatile Map<String, Map<String, Object>> cached;
    private volatile long expiresAt;

    public CapabilitySchemaService(AgentHttpProperties properties, HttpAgentSupport support,
                                   ObjectMapper mapper) {
        this.properties = properties;
        this.support = support;
        this.mapper = mapper;
    }

    public Map<String, Object> schemaFor(String capabilityId) {
        return schemas().get(capabilityId);
    }

    public Map<String, Map<String, Object>> schemas() {
        long now = System.currentTimeMillis();
        if (cached != null && now < expiresAt) {
            return cached;
        }
        Map<String, Map<String, Object>> result = new LinkedHashMap<>(fallback());
        if ("http".equalsIgnoreCase(properties.getMode())) {
            load(result, properties.getProperty());
            load(result, properties.getContract());
        }
        cached = result;
        expiresAt = now + 5 * 60_000;
        return result;
    }

    private void load(Map<String, Map<String, Object>> target, AgentHttpProperties.Endpoint endpoint) {
        try {
            HttpAgentSupport.HttpResult result = support.get(endpoint, "/agent/v1/manifest",
                    endpoint.getActorId(), null, false);
            if (result.status() < 200 || result.status() >= 300) {
                log.warn("拉取 Manifest schema 失败 agent={} status={}",
                        endpoint.getBaseUrl(), result.status());
                return;
            }
            JsonNode body = result.body();
            JsonNode data = body.has("data") ? body.get("data") : body;
            JsonNode capabilities = data.get("capabilities");
            if (capabilities == null || !capabilities.isArray()) {
                return;
            }
            for (JsonNode capability : capabilities) {
                String id = text(capability, "capabilityId");
                if (id == null) {
                    id = text(capability, "taskType");
                }
                JsonNode schema = capability.get("inputSchema");
                if (id != null && schema != null && schema.isObject()) {
                    target.put(id, mapper.convertValue(schema,
                            new TypeReference<Map<String, Object>>() {
                            }));
                }
            }
        } catch (Exception ex) {
            log.warn("拉取 Manifest schema 异常 agent={}：{}", endpoint.getBaseUrl(), ex.getMessage());
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private Map<String, Map<String, Object>> fallback() {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        result.put("property.ticket.query", Map.of(
                "type", "object",
                "properties", Map.of(
                        "status", Map.of("type", "string",
                                "description", "工单状态：PENDING/ASSIGNED/ACCEPTED/RESOLVED/CLOSED"),
                        "keyword", Map.of("type", "string",
                                "description", "自然语言关键词，如地点/楼栋/故障描述"),
                        "ticketNo", Map.of("type", "string"),
                        "limit", Map.of("type", "integer"))));
        result.put("property.ticket.assign", Map.of(
                "type", "object",
                "required", List.of("ticketNo", "assigneeId"),
                "properties", Map.of(
                        "ticketNo", Map.of("type", "string"),
                        "assigneeId", Map.of("type", "string"))));
        for (String id : List.of("property.ticket.detail", "property.ticket.close",
                "property.ticket.accept", "property.ticket.resolve",
                "property.ticket.verify", "property.ticket.hold",
                "property.ticket.resume", "property.ticket.finish",
                "property.ticket.cancel", "property.ticket.comment.add",
                "property.ticket.material.add", "property.ticket.visit.add",
                "property.ticket.export")) {
            result.put(id, Map.of(
                    "type", "object",
                    "required", List.of("ticketNo"),
                    "properties", Map.of("ticketNo", Map.of("type", "string"))));
        }
        result.put("property.ticket.create", Map.of(
                "type", "object",
                "required", List.of("ticketTypeCode"),
                "properties", Map.of(
                        "ticketTypeCode", Map.of("type", "string"),
                        "description", Map.of("type", "string"),
                        "locationText", Map.of("type", "string"),
                        "priorityCode", Map.of("type", "string"))));
        result.put("property.profile.update", Map.of(
                "type", "object",
                "required", List.of("contactName"),
                "properties", Map.of(
                        "contactName", Map.of("type", "string"),
                        "phone", Map.of("type", "string"),
                        "projectId", Map.of("type", "integer"),
                        "buildingId", Map.of("type", "integer"),
                        "roomId", Map.of("type", "integer"),
                        "customerId", Map.of("type", "integer"))));
        result.put("gis.parcel.query", Map.of(
                "type", "object",
                "properties", Map.of(
                        "keyword", Map.of("type", "string"),
                        "parcelNo", Map.of("type", "string"))));
        result.put("gis.change.query", Map.of(
                "type", "object",
                "properties", Map.of(
                        "keyword", Map.of("type", "string"),
                        "parcelNo", Map.of("type", "string"),
                        "level", Map.of("type", "string",
                                "description", "风险等级：HIGH/GENERAL"))));
        result.put("gis.patrol.create", Map.of(
                "type", "object",
                "required", List.of("parcelNo"),
                "properties", Map.of(
                        "parcelNo", Map.of("type", "string"),
                        "patrolType", Map.of("type", "string"),
                        "deadline", Map.of("type", "string"))));
        result.put("inspect.report.query", Map.of(
                "type", "object",
                "properties", Map.of("query", Map.of("type", "string"))));
        result.put("inspect.report.generate", Map.of(
                "type", "object",
                "required", List.of("reportType"),
                "properties", Map.of(
                        "reportType", Map.of("type", "string"),
                        "region", Map.of("type", "string"),
                        "period", Map.of("type", "string"))));
        result.put("inspect.plan.create", Map.of(
                "type", "object",
                "required", List.of("planName"),
                "properties", Map.of(
                        "planName", Map.of("type", "string"),
                        "region", Map.of("type", "string"),
                        "deadline", Map.of("type", "string"))));
        result.put("inspect.report.export", Map.of(
                "type", "object",
                "properties", Map.of("query", Map.of("type", "string"))));
        return result;
    }
}
