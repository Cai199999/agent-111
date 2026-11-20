package com.wuye.agent.api;

import com.wuye.agent.agent.catalog.AgentCatalogService;
import com.wuye.agent.auth.AuthContext;
import com.wuye.agent.common.ApiResponse;
import com.wuye.agent.common.Ids;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前用户可见的 Agent/能力目录（权限裁剪后）。
 */
@RestController
@RequestMapping("/api/v1/agents")
public class AgentCatalogController {

    private final AgentCatalogService catalogService;
    private final Ids ids;

    public AgentCatalogController(AgentCatalogService catalogService, Ids ids) {
        this.catalogService = catalogService;
        this.ids = ids;
    }

    @GetMapping
    public ApiResponse<List<AgentCatalogService.AgentView>> agents(
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        String trace = traceId == null || traceId.isBlank() ? ids.traceId() : traceId;
        return ApiResponse.ok(catalogService.forUser(AuthContext.require()), trace);
    }
}
