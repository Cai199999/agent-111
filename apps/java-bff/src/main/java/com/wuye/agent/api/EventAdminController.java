package com.wuye.agent.api;

import com.wuye.agent.auth.AuthContext;
import com.wuye.agent.auth.PolicyService;
import com.wuye.agent.common.ApiResponse;
import com.wuye.agent.common.Ids;
import com.wuye.agent.event.EventBus;
import com.wuye.agent.event.OutboxEventPublisher;
import com.wuye.agent.flywheel.FlywheelSample;
import com.wuye.agent.flywheel.FlywheelSampleService;
import com.wuye.agent.orchestration.OrchestrationAuditStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 事件与飞轮排障接口（ADMIN）。
 *
 * <p>只暴露事件信封摘要与样本，不暴露确认令牌/密钥；事件缓冲区固定上限。</p>
 */
@RestController
@RequestMapping("/api/v1/admin")
public class EventAdminController {

    private final EventBus eventBus;
    private final OutboxEventPublisher outboxPublisher;
    private final FlywheelSampleService flywheel;
    private final OrchestrationAuditStore orchestrationAudit;
    private final PolicyService policyService;
    private final Ids ids;

    public EventAdminController(EventBus eventBus, OutboxEventPublisher outboxPublisher,
                                FlywheelSampleService flywheel,
                                OrchestrationAuditStore orchestrationAudit,
                                PolicyService policyService, Ids ids) {
        this.eventBus = eventBus;
        this.outboxPublisher = outboxPublisher;
        this.flywheel = flywheel;
        this.orchestrationAudit = orchestrationAudit;
        this.policyService = policyService;
        this.ids = ids;
    }

    @GetMapping("/events/recent")
    public ApiResponse<Map<String, Object>> recentEvents(
            @RequestParam(defaultValue = "50") int limit,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        policyService.requireAdmin(AuthContext.require());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("events", eventBus.recent(limit));
        data.put("stats", eventBus.stats());
        data.put("outbox", outboxPublisher.stats());
        // 重启后进程内缓冲为空，用 Outbox 历史补足管理端观测。
        data.put("outboxEvents", outboxPublisher.recent(limit));
        return ApiResponse.ok(data, trace(traceId));
    }

    @GetMapping("/flywheel/samples")
    public ApiResponse<Map<String, Object>> flywheelSamples(
            @RequestParam(defaultValue = "20") int limit,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        policyService.requireAdmin(AuthContext.require());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("samples", flywheel.recent(limit));
        data.put("stats", flywheel.stats());
        return ApiResponse.ok(data, trace(traceId));
    }

    @GetMapping("/orchestration/recent")
    public ApiResponse<Map<String, Object>> orchestrationRecent(
            @RequestParam(defaultValue = "20") int limit,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        policyService.requireAdmin(AuthContext.require());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("traces", orchestrationAudit.recent(limit));
        data.put("stats", orchestrationAudit.stats());
        return ApiResponse.ok(data, trace(traceId));
    }

    /** 离线评测导出：供 `scripts/eval_flywheel.py --api` 使用，单次最多 2000 条。 */
    @GetMapping("/flywheel/export")
    public ApiResponse<List<FlywheelSample>> flywheelExport(
            @RequestParam(defaultValue = "500") int limit,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        policyService.requireAdmin(AuthContext.require());
        return ApiResponse.ok(flywheel.export(limit), trace(traceId));
    }

    private String trace(String traceId) {
        return traceId == null || traceId.isBlank() ? ids.traceId() : traceId;
    }
}
