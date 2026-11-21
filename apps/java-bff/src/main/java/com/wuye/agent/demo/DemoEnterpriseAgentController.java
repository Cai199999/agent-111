package com.wuye.agent.demo;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 企业 Agent 示例的“真实 HTTP 端点”。
 *
 * <p>路径：{@code /demo-agents/{policy|customer}/...}，遵循 Canonical v1：
 * Agent Card、health/ready、tasks、confirm、cancel、events。
 * 仅用于演示，返回 Mock 数据；不执行任意代码。</p>
 */
@RestController
@RequestMapping("/demo-agents/{agentId}")
public class DemoEnterpriseAgentController {

    private final DemoEnterpriseAgentService service;

    public DemoEnterpriseAgentController(DemoEnterpriseAgentService service) {
        this.service = service;
    }

    @GetMapping("/.well-known/agent-card.json")
    public ResponseEntity<Map<String, Object>> card(@PathVariable String agentId) {
        Map<String, Object> card = service.card(agentId);
        return card == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(card);
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health(@PathVariable String agentId) {
        Map<String, Object> health = service.health(agentId);
        return health == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(health);
    }

    @GetMapping("/ready")
    public ResponseEntity<Map<String, Object>> ready(@PathVariable String agentId) {
        return health(agentId);
    }

    @PostMapping("/agent/v1/tasks")
    public ResponseEntity<Map<String, Object>> submit(
            @PathVariable String agentId,
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> result = service.submit(agentId, body == null ? Map.of() : body);
        return result == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(result);
    }

    @GetMapping("/agent/v1/tasks/{taskId}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String agentId,
                                                   @PathVariable String taskId) {
        Map<String, Object> result = service.get(agentId, taskId);
        return result == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(result);
    }

    @PostMapping("/agent/v1/tasks/{taskId}/confirm")
    public ResponseEntity<Map<String, Object>> confirm(
            @PathVariable String agentId, @PathVariable String taskId,
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> result = service.confirm(agentId, taskId, body == null ? Map.of() : body);
        return result == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(result);
    }

    @PostMapping("/agent/v1/tasks/{taskId}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(
            @PathVariable String agentId, @PathVariable String taskId,
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> result = service.cancel(agentId, taskId, body == null ? Map.of() : body);
        return result == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(result);
    }

    @GetMapping("/agent/v1/tasks/{taskId}/events")
    public ResponseEntity<Map<String, Object>> events(
            @PathVariable String agentId, @PathVariable String taskId,
            @RequestParam(defaultValue = "0") long afterSeq) {
        Map<String, Object> result = service.events(agentId, taskId, afterSeq);
        return result == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(result);
    }
}
