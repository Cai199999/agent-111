package com.wuye.agent.agent.model;

import java.time.Instant;
import java.util.Map;

/**
 * 统一任务事件。Adapter 产出子 Agent 原生 seq；TaskService 会重新分配主 Agent seq。
 */
public record TaskEvent(
        long seq,
        String type,
        TaskStatus status,
        Map<String, Object> payload,
        Instant createdAt
) {
}
