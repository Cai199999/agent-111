package com.wuye.agent.profile;

import com.wuye.agent.agent.adapter.AdapterRegistry;
import com.wuye.agent.agent.model.Capability;
import com.wuye.agent.agent.model.TaskStatus;
import com.wuye.agent.api.TaskView;
import com.wuye.agent.auth.AuthContext;
import com.wuye.agent.auth.AuthUser;
import com.wuye.agent.auth.PolicyService;
import com.wuye.agent.common.ApiException;
import com.wuye.agent.common.ApiResponse;
import com.wuye.agent.common.Ids;
import com.wuye.agent.memory.UserProfileService;
import com.wuye.agent.store.TaskRecord;
import com.wuye.agent.task.TaskService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 个人中心：表单定义来自子 Agent，主 Agent 只收集并转交 profile.update。
 */
@RestController
@RequestMapping("/api/v1/profile")
public class ProfileController {

    private final TaskService taskService;
    private final AdapterRegistry registry;
    private final PolicyService policyService;
    private final PropertyManifestService manifestService;
    private final AgentManifestService agentManifestService;
    private final UserProfileService userProfileService;
    private final Ids ids;

    public ProfileController(TaskService taskService, AdapterRegistry registry,
                             PolicyService policyService, PropertyManifestService manifestService,
                             AgentManifestService agentManifestService,
                             UserProfileService userProfileService, Ids ids) {
        this.taskService = taskService;
        this.registry = registry;
        this.policyService = policyService;
        this.manifestService = manifestService;
        this.agentManifestService = agentManifestService;
        this.userProfileService = userProfileService;
        this.ids = ids;
    }

    @GetMapping("/form")
    public ApiResponse<Map<String, Object>> form(
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        AuthUser user = AuthContext.require();
        policyService.requireCapability(user, "property.profile.get");
        return ApiResponse.ok(manifestService.personalInfoForm(), trace(traceId));
    }

    /**
     * H2：聚合资料段。物业段保持向后兼容；运行时安装的 Agent 通过 Manifest 声明表单。
     */
    @GetMapping("/forms")
    public ApiResponse<Map<String, Object>> forms(
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        AuthUser user = AuthContext.require();
        List<Map<String, Object>> sections = agentManifestService.sections().stream()
                .filter(section -> allowed(user, section))
                .toList();
        return ApiResponse.ok(Map.of("sections", sections), trace(traceId));
    }

    @GetMapping("/forms/{agentKey}")
    public ApiResponse<Map<String, Object>> formSection(
            @PathVariable String agentKey,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        AuthUser user = AuthContext.require();
        Map<String, Object> section = agentManifestService.section(agentKey)
                .orElseThrow(() -> ApiException.notFound("PROFILE_FORM_NOT_FOUND",
                        "资料表单不存在：" + agentKey));
        requireRead(user, section);
        Map<String, Object> result = new LinkedHashMap<>(section);
        String getCapability = text(section.get("getCapability"));
        if (getCapability != null) {
            result.put("data", runSync(user, getCapability, Map.of(), trace(traceId)));
        }
        return ApiResponse.ok(result, trace(traceId));
    }

    @PostMapping("/forms/{agentKey}")
    public ApiResponse<TaskView> updateSection(
            @PathVariable String agentKey,
            @RequestBody Map<String, Object> input,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        AuthUser user = AuthContext.require();
        Map<String, Object> section = agentManifestService.section(agentKey)
                .orElseThrow(() -> ApiException.notFound("PROFILE_FORM_NOT_FOUND",
                        "资料表单不存在：" + agentKey));
        String updateCapability = text(section.get("updateCapability"));
        if (updateCapability == null) {
            throw ApiException.badRequest("PROFILE_FORM_READONLY", "该资料表单不支持提交");
        }
        Capability capability = registry.capability(updateCapability)
                .orElseThrow(() -> ApiException.notFound("CAPABILITY_NOT_FOUND",
                        "个人资料更新能力未注册：" + updateCapability));
        policyService.requireCapability(user, capability.capabilityId());
        TaskRecord task = taskService.submit("profile:" + user.id(), capability, input,
                user.actorId(), trace(traceId));
        return ApiResponse.ok(TaskView.from(task), trace(traceId));
    }

    @GetMapping("/options")
    public ApiResponse<Object> options(
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        return ApiResponse.ok(runSync(AuthContext.require(), "property.masterdata.options",
                Map.of(), trace(traceId)), trace(traceId));
    }

    @GetMapping
    public ApiResponse<Object> profile(
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        return ApiResponse.ok(runSync(AuthContext.require(), "property.profile.get",
                Map.of(), trace(traceId)), trace(traceId));
    }

    @GetMapping("/memory")
    public ApiResponse<Map<String, Object>> memory(
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        AuthUser user = AuthContext.require();
        return ApiResponse.ok(Map.of(
                "profile", userProfileService.getProfile(user.id()),
                "meta", userProfileService.getMeta(user.id()) == null ? Map.of() : userProfileService.getMeta(user.id())
        ), trace(traceId));
    }

    @DeleteMapping("/memory")
    public ApiResponse<Map<String, Object>> clearMemory(
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        AuthUser user = AuthContext.require();
        userProfileService.delete(user.id());
        return ApiResponse.ok(Map.of("deleted", true), trace(traceId));
    }

    @PostMapping
    public ApiResponse<TaskView> update(@RequestBody Map<String, Object> input,
                                        @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        AuthUser user = AuthContext.require();
        Capability capability = registry.capability("property.profile.update")
                .orElseThrow(() -> ApiException.notFound("CAPABILITY_NOT_FOUND", "个人资料更新能力未注册"));
        policyService.requireCapability(user, capability.capabilityId());
        TaskRecord task = taskService.submit("profile:" + user.id(), capability, input,
                user.actorId(), trace(traceId));
        return ApiResponse.ok(TaskView.from(task), trace(traceId));
    }

    private boolean allowed(AuthUser user, Map<String, Object> section) {
        if (user == null) {
            return false;
        }
        if (user.isAdmin()) {
            return true;
        }
        String getCapability = text(section.get("getCapability"));
        String updateCapability = text(section.get("updateCapability"));
        boolean canRead = getCapability == null || policyService.canUseCapability(user, getCapability);
        boolean canWrite = updateCapability == null || policyService.canUseCapability(user, updateCapability);
        return canRead || canWrite;
    }

    private void requireRead(AuthUser user, Map<String, Object> section) {
        String getCapability = text(section.get("getCapability"));
        if (getCapability != null) {
            policyService.requireCapability(user, getCapability);
        }
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private Object runSync(AuthUser user, String capabilityId, Map<String, Object> input, String traceId) {
        Capability capability = registry.capability(capabilityId)
                .orElseThrow(() -> ApiException.notFound("CAPABILITY_NOT_FOUND", "能力未注册：" + capabilityId));
        policyService.requireCapability(user, capability.capabilityId());
        TaskRecord task = taskService.submit("profile:" + user.id(), capability, input,
                user.actorId(), traceId);
        if (task.status == TaskStatus.SUCCEEDED) {
            return task.output;
        }
        if (task.error != null) {
            throw new ApiException(task.error.code(), task.error.category().equals("DEPENDENCY") ? 503 : 400,
                    task.error.message());
        }
        throw new ApiException("TASK_NOT_COMPLETED", 503, "个人资料任务未完成：" + task.status);
    }

    private String trace(String traceId) {
        return traceId == null || traceId.isBlank() ? ids.traceId() : traceId;
    }
}
