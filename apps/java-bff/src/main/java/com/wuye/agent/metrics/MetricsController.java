package com.wuye.agent.metrics;

import com.wuye.agent.auth.AuthContext;
import com.wuye.agent.auth.PolicyService;
import com.wuye.agent.common.ApiResponse;
import com.wuye.agent.common.Ids;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端度量聚合。仅 ADMIN 可访问。
 */
@RestController
@RequestMapping("/api/v1/admin/metrics")
public class MetricsController {

    private final MetricsService metricsService;
    private final PolicyService policyService;
    private final Ids ids;

    public MetricsController(MetricsService metricsService, PolicyService policyService, Ids ids) {
        this.metricsService = metricsService;
        this.policyService = policyService;
        this.ids = ids;
    }

    @GetMapping("/summary")
    public ApiResponse<MetricsService.MetricsSummary> summary(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        policyService.requireAdmin(AuthContext.require());
        Instant toInstant = parse(to, Instant.now());
        Instant fromInstant = parse(from, toInstant.minus(7, ChronoUnit.DAYS));
        return ApiResponse.ok(metricsService.summary(fromInstant, toInstant), trace(traceId));
    }

    @GetMapping("/trend")
    public ApiResponse<List<MetricsService.TrendPoint>> trend(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        policyService.requireAdmin(AuthContext.require());
        Instant toInstant = parse(to, Instant.now());
        Instant fromInstant = parse(from, toInstant.minus(7, ChronoUnit.DAYS));
        return ApiResponse.ok(metricsService.trend(fromInstant, toInstant), trace(traceId));
    }

    private Instant parse(String value, Instant fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Instant.parse(value);
        } catch (Exception ex) {
            return fallback;
        }
    }

    private String trace(String traceId) {
        return traceId == null || traceId.isBlank() ? ids.traceId() : traceId;
    }
}
