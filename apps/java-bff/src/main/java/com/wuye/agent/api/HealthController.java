package com.wuye.agent.api;

import com.wuye.agent.routing.AiProperties;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 原型健康检查。生产化后 /ready 还需检查 MySQL、Redis、MinIO、Python AI。
 */
@RestController
public class HealthController {

    private final AiProperties aiProperties;

    public HealthController(AiProperties aiProperties) {
        this.aiProperties = aiProperties;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("service", "wuye-agent-bff");
        body.put("mode", "prototype");
        body.put("time", OffsetDateTime.now().toString());
        return body;
    }

    @GetMapping("/ready")
    public Map<String, Object> ready() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("checks", Map.of(
                "store", "UP",
                "agents", "MOCK",
                "llm", aiProperties.isEnabled() ? "LLM_ENABLED" : "RULE_ONLY"
        ));
        return body;
    }
}
