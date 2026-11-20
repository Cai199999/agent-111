package com.wuye.agent.human;

import com.wuye.agent.api.TaskView;
import com.wuye.agent.audit.AuditService;
import com.wuye.agent.auth.AuthContext;
import com.wuye.agent.auth.AuthUser;
import com.wuye.agent.auth.PolicyService;
import com.wuye.agent.common.ApiException;
import com.wuye.agent.common.ApiResponse;
import com.wuye.agent.common.Ids;
import com.wuye.agent.store.TaskRecord;
import com.wuye.agent.task.TaskService;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 人工介入队列：工人/管理员认领并处理需要人工关注的 Agent 任务。
 */
@RestController
@RequestMapping("/api/v1/human-tasks")
public class HumanTaskController {

    private final HumanInterventionService humanService;
    private final TaskService taskService;
    private final PolicyService policyService;
    private final AuditService auditService;
    private final Ids ids;

    public HumanTaskController(HumanInterventionService humanService, TaskService taskService,
                               PolicyService policyService, AuditService auditService, Ids ids) {
        this.humanService = humanService;
        this.taskService = taskService;
        this.policyService = policyService;
        this.auditService = auditService;
        this.ids = ids;
    }

    @GetMapping
    public ApiResponse<List<HumanTaskView>> list(
            @RequestParam(defaultValue = "PENDING") String status,
            @RequestParam(defaultValue = "50") int limit,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        AuthUser user = AuthContext.require();
        policyService.requirePermission(user, "human:manage");
        List<HumanTaskView> items = humanService.list(status, limit).stream()
                .map(record -> new HumanTaskView(record, taskOrNull(record.taskNo())))
                .toList();
        return ApiResponse.ok(items, trace(traceId));
    }

    @PostMapping("/{taskNo}/claim")
    public ApiResponse<HumanTaskView> claim(@PathVariable String taskNo,
                                            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        AuthUser user = AuthContext.require();
        policyService.requirePermission(user, "human:manage");
        humanService.claim(taskNo, user.id(), user.username());
        auditService.record("HUMAN_CLAIM", "HUMAN_TASK", taskNo, "SUCCESS",
                Map.of("assignee", user.username()));
        HumanTaskRecord record = humanService.get(taskNo)
                .orElseThrow(() -> ApiException.notFound("HUMAN_TASK_NOT_FOUND", "人工任务不存在"));
        return ApiResponse.ok(new HumanTaskView(record, taskOrNull(taskNo)), trace(traceId));
    }

    @PostMapping("/{taskNo}/resolve")
    public ApiResponse<HumanTaskView> resolve(@PathVariable String taskNo,
                                              @RequestBody(required = false) ResolveRequest request,
                                              @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        AuthUser user = AuthContext.require();
        policyService.requirePermission(user, "human:manage");
        String note = request == null || request.note() == null ? "人工处理完成" : request.note();
        humanService.resolve(taskNo, note);
        auditService.record("HUMAN_RESOLVE", "HUMAN_TASK", taskNo, "SUCCESS",
                Map.of("note", note));
        HumanTaskRecord record = humanService.get(taskNo)
                .orElseThrow(() -> ApiException.notFound("HUMAN_TASK_NOT_FOUND", "人工任务不存在"));
        return ApiResponse.ok(new HumanTaskView(record, taskOrNull(taskNo)), trace(traceId));
    }

    private TaskView taskOrNull(String taskNo) {
        try {
            TaskRecord task = taskService.get(taskNo);
            return task == null ? null : TaskView.from(task);
        } catch (Exception ex) {
            return null;
        }
    }

    private String trace(String traceId) {
        return traceId == null || traceId.isBlank() ? ids.traceId() : traceId;
    }

    public record HumanTaskView(HumanTaskRecord human, TaskView task) {
    }

    public record ResolveRequest(String note) {
    }
}
