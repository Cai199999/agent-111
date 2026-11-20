package com.wuye.agent.api;

import com.wuye.agent.agent.adapter.AdapterRegistry;
import com.wuye.agent.agent.catalog.AgentCatalogService;
import com.wuye.agent.agent.catalog.CapabilitySchemaService;
import com.wuye.agent.agent.model.Capability;
import com.wuye.agent.auth.AuthContext;
import com.wuye.agent.auth.AuthUser;
import com.wuye.agent.auth.PolicyService;
import com.wuye.agent.common.ApiException;
import com.wuye.agent.common.ApiResponse;
import com.wuye.agent.common.Ids;
import com.wuye.agent.store.TaskRecord;
import com.wuye.agent.task.TaskService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 把子 Agent 能力作为“工具”暴露给任意调用方（主 Agent、外部 orchestrator、MCP bridge）。
 *
 * <p>工具结果原样返回 TaskView.output，不在主 Agent 侧改写。</p>
 */
@RestController
@RequestMapping("/api/v1/tools")
public class ToolController {

    private final AdapterRegistry registry;
    private final AgentCatalogService catalogService;
    private final CapabilitySchemaService schemaService;
    private final PolicyService policyService;
    private final TaskService taskService;
    private final Ids ids;

    public ToolController(AdapterRegistry registry, AgentCatalogService catalogService,
                          CapabilitySchemaService schemaService, PolicyService policyService,
                          TaskService taskService, Ids ids) {
        this.registry = registry;
        this.catalogService = catalogService;
        this.schemaService = schemaService;
        this.policyService = policyService;
        this.taskService = taskService;
        this.ids = ids;
    }

    @GetMapping
    public ApiResponse<List<ToolView>> tools(
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        AuthUser user = AuthContext.require();
        List<ToolView> tools = catalogService.capabilitiesForUser(user).stream()
                .map(capability -> new ToolView(
                        capability.capabilityId(),
                        capability.displayName(),
                        capability.description(),
                        capability.readOnly(),
                        capability.requiresConfirmation(),
                        capability.executionModes(),
                        schemaService.schemaFor(capability.capabilityId())))
                .toList();
        return ApiResponse.ok(tools, trace(traceId));
    }

    @GetMapping("/{toolName}")
    public ApiResponse<ToolView> tool(@PathVariable String toolName,
                                      @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        AuthUser user = AuthContext.require();
        Capability capability = requireTool(user, toolName);
        return ApiResponse.ok(new ToolView(
                capability.capabilityId(),
                capability.displayName(),
                capability.description(),
                capability.readOnly(),
                capability.requiresConfirmation(),
                capability.executionModes(),
                schemaService.schemaFor(capability.capabilityId())), trace(traceId));
    }

    @PostMapping("/{toolName}/execute")
    public ApiResponse<TaskView> execute(@PathVariable String toolName,
                                         @RequestBody(required = false) Map<String, Object> input,
                                         @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        AuthUser user = AuthContext.require();
        Capability capability = requireTool(user, toolName);
        TaskRecord task = taskService.submit("tool:" + user.id(), capability,
                input == null ? Map.of() : input, user.actorId(), trace(traceId));
        return ApiResponse.ok(TaskView.from(task), trace(traceId));
    }

    private Capability requireTool(AuthUser user, String toolName) {
        Capability capability = registry.capability(toolName)
                .orElseThrow(() -> ApiException.notFound("CAPABILITY_NOT_FOUND", "工具不存在：" + toolName));
        policyService.requireCapability(user, capability.capabilityId());
        return capability;
    }

    private String trace(String traceId) {
        return traceId == null || traceId.isBlank() ? ids.traceId() : traceId;
    }

    public record ToolView(
            String name,
            String displayName,
            String description,
            boolean readOnly,
            boolean requiresConfirmation,
            List<String> executionModes,
            Map<String, Object> inputSchema
    ) {
    }
}
