package com.wuye.agent.profile;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuye.agent.agent.http.AgentHttpProperties;
import com.wuye.agent.agent.http.HttpAgentSupport;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 从物业子 Agent 的 Manifest 读取 forms.personalInfo。
 *
 * <p>表单字段由子 Agent 定义，主 Agent 只做渲染；Manifest 不可达时才使用本地兜底，
 * 并在 source 标记 fallback，提醒前端/运维。</p>
 */
@Service
public class PropertyManifestService {

    private static final Logger log = LoggerFactory.getLogger(PropertyManifestService.class);

    private final AgentHttpProperties properties;
    private final HttpAgentSupport support;
    private final ObjectMapper mapper;
    private volatile Map<String, Object> cached;
    private volatile long expiresAt;

    public PropertyManifestService(AgentHttpProperties properties, HttpAgentSupport support,
                                   ObjectMapper mapper) {
        this.properties = properties;
        this.support = support;
        this.mapper = mapper;
    }

    public Map<String, Object> personalInfoForm() {
        long now = System.currentTimeMillis();
        if (cached != null && now < expiresAt) {
            return cached;
        }
        if (!"http".equalsIgnoreCase(properties.getMode())) {
            cached = fallback("mock-mode");
            expiresAt = now + 30_000;
            return cached;
        }
        try {
            AgentHttpProperties.Endpoint endpoint = properties.getProperty();
            HttpAgentSupport.HttpResult result = support.get(endpoint, "/agent/v1/manifest",
                    endpoint.getActorId(), null, false);
            if (result.status() >= 200 && result.status() < 300) {
                JsonNode body = result.body();
                JsonNode data = body.has("data") ? body.get("data") : body;
                JsonNode forms = data.get("forms");
                JsonNode personal = forms == null ? null : forms.get("personalInfo");
                if (personal != null && personal.isObject()) {
                    Map<String, Object> form = mapper.convertValue(personal,
                            new TypeReference<Map<String, Object>>() {
                            });
                    form.put("source", "manifest");
                    cached = form;
                    expiresAt = now + 5 * 60_000;
                    return form;
                }
            }
            log.warn("拉取物业 Manifest forms.personalInfo 失败 status={}，使用兜底表单",
                    result.status());
        } catch (Exception ex) {
            log.warn("拉取物业 Manifest 失败，使用兜底表单：{}", ex.getMessage());
        }
        cached = fallback("fallback");
        expiresAt = now + 30_000;
        return cached;
    }

    private Map<String, Object> fallback(String source) {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("title", "个人信息");
        form.put("readTaskType", "profile.get");
        form.put("taskType", "profile.update");
        form.put("optionsTaskType", "masterdata.options");
        form.put("fields", List.of(
                field("contactName", "联系人", "text", true, 100, false, null, null),
                field("phone", "联系电话", "text", false, 32, true, null, null),
                field("projectId", "项目", "select", false, null, false, "projects", null),
                field("buildingId", "楼宇", "select", false, null, false, "buildings", "projectId"),
                field("roomId", "房间", "select", false, null, false, "rooms", "buildingId"),
                field("customerId", "客户", "select", false, null, false, "customers", "projectId")));
        form.put("review", Map.of("bindingChanges", "PENDING", "reviewPermission", "admin:user"));
        form.put("source", source);
        return form;
    }

    private Map<String, Object> field(String name, String label, String type, boolean required,
                                      Integer maxLength, boolean sensitive, String optionsKey,
                                      String dependsOn) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("name", name);
        field.put("label", label);
        field.put("type", type);
        field.put("required", required);
        if (maxLength != null) {
            field.put("maxLength", maxLength);
        }
        field.put("sensitive", sensitive);
        if (optionsKey != null) {
            field.put("optionsKey", optionsKey);
        }
        if (dependsOn != null) {
            field.put("dependsOn", dependsOn);
        }
        return field;
    }
}
