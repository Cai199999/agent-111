package com.wuye.agent.store;

import java.time.Instant;
import java.util.Map;

/**
 * 会话消息。metadata 存放路由、任务号、卡片等结构化信息。
 */
public record ChatMessage(
        String messageNo,
        String sessionNo,
        String role,
        String content,
        String contentType,
        Map<String, Object> metadata,
        Instant createdAt
) {
}
