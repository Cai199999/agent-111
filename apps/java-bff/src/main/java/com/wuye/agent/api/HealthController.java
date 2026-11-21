package com.wuye.agent.api;

import com.wuye.agent.agent.http.AgentHttpProperties;
import com.wuye.agent.routing.AiProperties;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康/就绪检查。当前 /ready 反映 store、LLM 开关和 Agent 模式；
 * 生产化还需纳入 Redis、MinIO、数据库连接池与下游依赖。
 */
@RestController
public class HealthController {

    private final AiProperties aiProperties;
    private final AgentHttpProperties agentProperties;

    public HealthController(AiProperties aiProperties, AgentHttpProperties agentProperties) {
        this.aiProperties = aiProperties;
        this.agentProperties = agentProperties;
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
                "agents", agentProperties.getMode().toUpperCase(),
                "llm", aiProperties.isEnabled() ? "LLM_ENABLED" : "RULE_ONLY"
        ));
        return body;
    }
}
