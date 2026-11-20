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
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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
    private final UserProfileService userProfileService;
    private final Ids ids;

    public ProfileController(TaskService taskService, AdapterRegistry registry,
                             PolicyService policyService, PropertyManifestService manifestService,
                             UserProfileService userProfileService, Ids ids) {
        this.taskService = taskService;
        this.registry = registry;
        this.policyService = policyService;
        this.manifestService = manifestService;
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
