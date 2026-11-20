package com.wuye.agent.api;

import com.wuye.agent.agent.model.Decision;
import com.wuye.agent.agent.model.TaskEvent;
import com.wuye.agent.auth.AuthContext;
import com.wuye.agent.auth.AuthUser;
import com.wuye.agent.common.ApiException;
import com.wuye.agent.common.ApiResponse;
import com.wuye.agent.common.Ids;
import com.wuye.agent.store.TaskRecord;
import com.wuye.agent.task.TaskService;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 任务查询、确认、取消、事件续传。
 */
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

    private final TaskService taskService;
    private final Ids ids;

    public TaskController(TaskService taskService, Ids ids) {
        this.taskService = taskService;
        this.ids = ids;
    }

    @GetMapping("/{taskNo}")
    public ApiResponse<TaskView> get(@PathVariable String taskNo,
                                     @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        TaskRecord task = taskService.get(taskNo);
        requireTaskAccess(task);
        return ApiResponse.ok(TaskView.from(task), trace(traceId));
    }

    @GetMapping
    public ApiResponse<List<TaskView>> list(@RequestParam(required = false) String sessionNo,
                                            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        AuthUser user = AuthContext.require();
        List<TaskRecord> tasks = taskService.list(sessionNo).stream()
                .filter(task -> user.isAdmin() || task.actorId.equals(user.actorId()))
                .toList();
        return ApiResponse.ok(tasks.stream().map(TaskView::from).toList(), trace(traceId));
    }

    @PostMapping("/{taskNo}/confirm")
    public ApiResponse<TaskView> confirm(@PathVariable String taskNo,
                                         @RequestBody ConfirmRequest request,
                                         @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        AuthUser user = AuthContext.require();
        requireTaskAccess(taskService.get(taskNo));
        Decision decision;
        try {
            decision = Decision.valueOf(request.decision() == null ? "" : request.decision().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("VALIDATION_ERROR", "decision 必须是 APPROVE 或 REJECT");
        }
        TaskRecord task = taskService.confirm(taskNo, decision, request.comment(),
                user.actorId(), user.isAdmin());
        return ApiResponse.ok(TaskView.from(task), trace(traceId));
    }

    @PostMapping("/{taskNo}/cancel")
    public ApiResponse<TaskView> cancel(@PathVariable String taskNo,
                                        @RequestBody(required = false) CancelRequest request,
                                        @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        AuthUser user = AuthContext.require();
        requireTaskAccess(taskService.get(taskNo));
        TaskRecord task = taskService.cancel(taskNo, request == null ? null : request.reason(),
                user.actorId(), user.isAdmin());
        return ApiResponse.ok(TaskView.from(task), trace(traceId));
    }

    @GetMapping("/{taskNo}/events")
    public ApiResponse<EventsView> events(@PathVariable String taskNo,
                                          @RequestParam(defaultValue = "0") long afterSeq,
                                          @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        requireTaskAccess(taskService.get(taskNo));
        List<TaskEvent> events = taskService.events(taskNo, afterSeq);
        long nextSeq = events.isEmpty() ? afterSeq : events.get(events.size() - 1).seq();
        boolean terminal = taskService.get(taskNo).terminal();
        return ApiResponse.ok(new EventsView(events, nextSeq, terminal), trace(traceId));
    }

    private void requireTaskAccess(TaskRecord task) {
        AuthUser user = AuthContext.require();
        if (task == null) {
            throw ApiException.notFound("TASK_NOT_FOUND", "任务不存在");
        }
        if (!user.isAdmin() && !task.actorId.equals(user.actorId())) {
            throw new ApiException("AUTH_FORBIDDEN", 403, "无权访问该任务");
        }
    }

    private String trace(String traceId) {
        return traceId == null || traceId.isBlank() ? ids.traceId() : traceId;
    }

    public record ConfirmRequest(String decision, String comment) {
    }

    public record CancelRequest(String reason) {
    }

    public record EventsView(List<TaskEvent> items, long nextSeq, boolean terminal) {
    }
}
