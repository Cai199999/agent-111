package com.wuye.agent.routing;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 多轮补参状态：LLM 追问后暂存能力、已填参数和缺失字段，
 * 下一轮用户回答时合并，不重新开始。
 */
public record PendingIntent(
        String capabilityId,
        Map<String, Object> args,
        List<String> missingFields,
        Instant updatedAt
) {
    public boolean expired() {
        return updatedAt == null || updatedAt.plusSeconds(600).isBefore(Instant.now());
    }
}
