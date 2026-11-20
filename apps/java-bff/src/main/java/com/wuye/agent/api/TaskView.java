package com.wuye.agent.api;

import com.wuye.agent.agent.model.AgentError;
import com.wuye.agent.agent.model.Confirmation;
import com.wuye.agent.agent.model.TaskEvent;
import com.wuye.agent.agent.model.TaskStatus;
import com.wuye.agent.store.TaskRecord;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务 API 视图。确认令牌不在此结构中，浏览器永远拿不到。
 */
public record TaskView(
        String taskNo,
        String sessionNo,
        String agentKey,
        String capabilityId,
        TaskStatus status,
        String executionMode,
        Map<String, Object> input,
        Object output,
        AgentError error,
        Map<String, Object> confirmation,
        String confirmationStatus,
        List<TaskEvent> events,
        int progress,
        String currentStep,
        boolean cancelSupported,
        boolean cancelable,
        String cancelReason,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt
) {
    public static TaskView from(TaskRecord task) {
        return new TaskView(
                task.taskNo, task.sessionNo, task.agentKey, task.capabilityId,
                task.status, task.executionMode, task.input, task.output, task.error,
                confirmationView(task.confirmation, task.input), task.confirmationStatus, task.events,
                task.progress, task.currentStep, task.cancelSupported, task.cancelable(),
                task.cancelReason, task.createdAt, task.startedAt, task.finishedAt);
    }

    private static Map<String, Object> confirmationView(Confirmation confirmation,
                                                        Map<String, Object> input) {
        if (confirmation == null) {
            return null;
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("title", confirmation.title());
        view.put("riskLevel", confirmation.riskLevel());
        view.put("fields", confirmationFields(confirmation, input));
        view.put("expiresAt", confirmation.expiresAt());
        view.put("status", confirmation.status());
        return view;
    }

    private static List<Map<String, Object>> confirmationFields(Confirmation confirmation,
                                                                Map<String, Object> input) {
        List<Map<String, Object>> fields = new java.util.ArrayList<>();
        if (confirmation.fields() != null) {
            for (Confirmation.Field field : confirmation.fields()) {
                fields.add(Map.of(
                        "label", field.label() == null ? "" : field.label(),
                        "value", field.value() == null ? "" : field.value()));
            }
        }
        if (fields.isEmpty() && input != null) {
            addField(fields, input, "description", "报修内容");
            addField(fields, input, "locationText", "位置");
            addField(fields, input, "ticketTypeCode", "工单类型");
            addField(fields, input, "priorityCode", "优先级");
            addField(fields, input, "contactName", "联系人");
        }
        return fields;
    }

    private static void addField(List<Map<String, Object>> fields, Map<String, Object> input,
                                 String key, String label) {
        Object value = input.get(key);
        if (value != null && !String.valueOf(value).isBlank()) {
            fields.add(Map.of("label", label, "value", String.valueOf(value)));
        }
    }
}
