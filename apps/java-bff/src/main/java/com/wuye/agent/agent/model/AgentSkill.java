package com.wuye.agent.agent.model;

import java.util.List;

/**
 * 子 Agent 的任务级 Skill（agent-task-v1 ≥ 0.11.0）。
 *
 * <p>Skill 是任务选择的封装层：skillId 非空时，taskType 必须属于该 Skill；
 * Skill 未覆盖或 partial=true 时回退单能力。Skill 不替代委托 scope 与本地授权。</p>
 */
public record AgentSkill(
        String agentKey,
        String skillId,
        String displayName,
        String description,
        List<String> taskTypes,
        List<String> capabilityIds,
        boolean partial,
        boolean requiresConfirmation
) {

    public AgentSkill {
        taskTypes = taskTypes == null ? List.of() : List.copyOf(taskTypes);
        capabilityIds = capabilityIds == null ? List.of() : List.copyOf(capabilityIds);
    }

    /** 是否覆盖某个能力/任务类型；能力 ID 或 taskType 任一命中即可。 */
    public boolean covers(String capabilityId, String taskType) {
        if (capabilityId != null && capabilityIds.contains(capabilityId)) {
            return true;
        }
        return taskType != null && taskTypes.contains(taskType);
    }
}
