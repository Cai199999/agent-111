package com.wuye.agent.agent.model;

import java.util.Map;

/**
 * Adapter 入参：只传能力、参数和链路信息，不传聊天历史。
 */
public record AdapterTaskRequest(
        String capabilityId,
        String taskType,
        Map<String, Object> input,
        String clientTaskId,
        String executionMode,
        String actorId,
        String traceId,
        String correlationId
) {
}
