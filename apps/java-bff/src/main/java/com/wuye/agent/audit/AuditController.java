package com.wuye.agent.audit;

import com.wuye.agent.auth.AuthContext;
import com.wuye.agent.auth.PolicyService;
import com.wuye.agent.common.ApiResponse;
import com.wuye.agent.common.Ids;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端审计查询。
 */
@RestController
@RequestMapping("/api/v1/admin/audit-logs")
public class AuditController {

    private final AuditStore store;
    private final PolicyService policyService;
    private final Ids ids;

    public AuditController(AuditStore store, PolicyService policyService, Ids ids) {
        this.store = store;
        this.policyService = policyService;
        this.ids = ids;
    }

    @GetMapping
    public ApiResponse<List<AuditEvent>> list(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String action,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        policyService.requireAdmin(AuthContext.require());
        String trace = traceId == null || traceId.isBlank() ? ids.traceId() : traceId;
        return ApiResponse.ok(store.recent(limit, action), trace);
    }
}
