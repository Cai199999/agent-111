package com.wuye.agent.routing;

import java.util.Map;

/**
 * LLM 根据子 Agent 校验错误修正后的参数或追问。
 */
public record RepairOutcome(
        String capabilityId,
        Map<String, Object> args,
        boolean needClarification,
        String clarification,
        String reason
) {
}
