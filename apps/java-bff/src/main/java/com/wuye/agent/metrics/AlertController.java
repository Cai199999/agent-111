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

@RestController
@RequestMapping("/api/v1/admin/alerts")
public class AlertController {

    private final AlertService alertService;
    private final PolicyService policyService;
    private final Ids ids;

    public AlertController(AlertService alertService, PolicyService policyService, Ids ids) {
        this.alertService = alertService;
        this.policyService = policyService;
        this.ids = ids;
    }

    @GetMapping
    public ApiResponse<List<AlertService.Alert>> alerts(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        policyService.requireAdmin(AuthContext.require());
        Instant toInstant = parse(to, Instant.now());
        Instant fromInstant = parse(from, toInstant.minus(7, ChronoUnit.DAYS));
        String trace = traceId == null || traceId.isBlank() ? ids.traceId() : traceId;
        return ApiResponse.ok(alertService.evaluate(fromInstant, toInstant), trace);
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
}
