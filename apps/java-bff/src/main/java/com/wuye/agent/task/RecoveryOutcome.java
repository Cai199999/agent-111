package com.wuye.agent.task;

import com.wuye.agent.agent.model.Capability;
import com.wuye.agent.store.TaskRecord;
import java.util.List;
import java.util.Map;

/**
 * A2A 自修复结果：最终任务、用户可读反馈、是否需要追问、追问时保留的上下文。
 *
 * <p>原则：不再把子 Agent 的原始错误直接抛给用户。能修复就修复；
 * 缺关键信息就追问；确实没有能力才兜底说“目前没有这个功能”。</p>
 */
public record RecoveryOutcome(
        TaskRecord task,
        Capability capability,
        String action,
        String message,
        boolean clarification,
        String pendingCapabilityId,
        Map<String, Object> pendingArgs,
        List<String> missingFields
) {

    public static RecoveryOutcome unchanged(TaskRecord task, Capability capability) {
        return new RecoveryOutcome(task, capability, "UNCHANGED", null,
                false, null, Map.of(), List.of());
    }

    public static RecoveryOutcome recovered(TaskRecord task, Capability capability, String action) {
        return new RecoveryOutcome(task, capability, action, null,
                false, null, Map.of(), List.of());
    }

    public static RecoveryOutcome clarify(TaskRecord task, Capability capability, String message,
                                          String pendingCapabilityId, Map<String, Object> pendingArgs,
                                          List<String> missingFields) {
        return new RecoveryOutcome(task, capability, "CLARIFY", message, true,
                pendingCapabilityId, pendingArgs == null ? Map.of() : pendingArgs,
                missingFields == null ? List.of() : missingFields);
    }

    public static RecoveryOutcome fallback(TaskRecord task, Capability capability,
                                           String action, String message) {
        return new RecoveryOutcome(task, capability, action, message,
                false, null, Map.of(), List.of());
    }
}
