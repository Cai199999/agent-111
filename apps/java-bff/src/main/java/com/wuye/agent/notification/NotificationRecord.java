package com.wuye.agent.notification;

import java.time.Instant;

/**
 * 站内通知：底座只负责存储与投递，不解释业务含义。
 */
public record NotificationRecord(
        String notificationNo,
        String recipientActorId,
        String tenantId,
        String sourceAgent,
        String category,
        String title,
        String content,
        String linkUrl,
        String bizType,
        String bizId,
        String dedupKey,
        String status,
        Instant createdAt,
        Instant readAt
) {
    public NotificationRecord read() {
        return new NotificationRecord(notificationNo, recipientActorId, tenantId, sourceAgent,
                category, title, content, linkUrl, bizType, bizId, dedupKey,
                "READ", createdAt, Instant.now());
    }
}
