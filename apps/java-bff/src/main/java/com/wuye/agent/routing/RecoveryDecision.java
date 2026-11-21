package com.wuye.agent.routing;

import java.util.List;
import java.util.Map;

/**
 * A2A 失败反馈后的自修复决策。
 *
 * <p>LLM 只决定动作，不执行：CLARIFY 由主 Agent 追问用户；
 * RETRY/ALTERNATIVE 由主 Agent 校验能力白名单后重试；NO_CAPABILITY 才兜底告知无此功能。</p>
 */
public record RecoveryDecision(
        String action,
        String capabilityId,
        Map<String, Object> args,
        String clarification,
        List<String> missingFields,
        String reason
) {
    public boolean isClarify() {
        return "CLARIFY".equalsIgnoreCase(action);
    }

    public boolean canRetry() {
        return ("RETRY".equalsIgnoreCase(action) || "ALTERNATIVE".equalsIgnoreCase(action))
                && capabilityId != null && !capabilityId.isBlank();
    }

    public boolean isNoCapability() {
        return "NO_CAPABILITY".equalsIgnoreCase(action);
    }
}
