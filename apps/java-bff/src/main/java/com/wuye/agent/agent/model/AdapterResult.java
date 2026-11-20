package com.wuye.agent.agent.model;

import java.util.List;

/**
 * Adapter 出参：方言差异在此收敛为统一任务结果。
 */
public record AdapterResult(
        String childTaskId,
        TaskStatus status,
        Object output,
        AgentError error,
        Confirmation confirmation,
        Integer progress,
        String currentStep,
        boolean cancelSupported,
        List<TaskEvent> events
) {
    public static AdapterResult success(String childTaskId, Object output, List<TaskEvent> events) {
        return new AdapterResult(childTaskId, TaskStatus.SUCCEEDED, output, null, null, 100, "DONE", true, events);
    }

    public static AdapterResult pending(String childTaskId, int progress, String step, List<TaskEvent> events) {
        return new AdapterResult(childTaskId, TaskStatus.PENDING, null, null, null, progress, step, true, events);
    }

    public static AdapterResult waiting(String childTaskId, Confirmation confirmation, List<TaskEvent> events) {
        return new AdapterResult(childTaskId, TaskStatus.WAITING_CONFIRM, null, null, confirmation, 0, "WAITING_CONFIRM", true, events);
    }

    public static AdapterResult failed(String childTaskId, AgentError error, List<TaskEvent> events) {
        return new AdapterResult(childTaskId, TaskStatus.FAILED, null, error, null, null, "FAILED", true, events);
    }
}
