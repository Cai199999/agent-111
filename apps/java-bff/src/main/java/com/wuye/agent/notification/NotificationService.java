package com.wuye.agent.notification;

import com.wuye.agent.common.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

/**
 * 通知中心：统一生成编号、去重、落库与实时推送。
 */
@Service
public class NotificationService {

    private final NotificationStore store;
    private final NotificationStreamService streamService;

    public NotificationService(NotificationStore store,
                               NotificationStreamService streamService) {
        this.store = store;
        this.streamService = streamService;
    }

    public NotificationRecord create(CreateCommand command) {
        if (isBlank(command.recipientActorId())) {
            throw ApiException.badRequest("NOTIFY_RECIPIENT_REQUIRED", "缺少接收人 actorId");
        }
        if (isBlank(command.title())) {
            throw ApiException.badRequest("NOTIFY_TITLE_REQUIRED", "缺少通知标题");
        }
        String dedupKey = isBlank(command.dedupKey())
                ? "auto:" + UUID.randomUUID()
                : truncate(command.dedupKey(), 160);
        var existing = store.findByDedup(dedupKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        NotificationRecord record = new NotificationRecord(
                "ntf_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24),
                command.recipientActorId().trim(),
                isBlank(command.tenantId()) ? "0" : truncate(command.tenantId(), 64),
                isBlank(command.sourceAgent()) ? "unknown" : truncate(command.sourceAgent(), 64),
                isBlank(command.category()) ? "SYSTEM" : truncate(command.category(), 32),
                truncate(command.title(), 256),
                truncate(command.content() == null ? "" : command.content(), 1024),
                command.linkUrl() == null ? null : truncate(command.linkUrl(), 512),
                command.bizType() == null ? null : truncate(command.bizType(), 64),
                command.bizId() == null ? null : truncate(command.bizId(), 64),
                dedupKey,
                "UNREAD",
                Instant.now(),
                null);
        NotificationRecord saved = store.insertIfAbsent(record);
        streamService.publish(saved);
        return saved;
    }

    public Map<String, Object> list(String actorId, String status, int limit) {
        List<NotificationRecord> items = store.list(actorId, status, limit);
        return Map.of("items", items, "unread", store.unreadCount(actorId));
    }

    public long unreadCount(String actorId) {
        return store.unreadCount(actorId);
    }

    public Map<String, Object> markRead(String actorId, String notificationNo) {
        boolean changed = store.markRead(actorId, notificationNo);
        return Map.of("changed", changed, "unread", store.unreadCount(actorId));
    }

    public Map<String, Object> markAllRead(String actorId) {
        int changed = store.markAllRead(actorId);
        return Map.of("changed", changed, "unread", store.unreadCount(actorId));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    public record CreateCommand(
            String recipientActorId,
            String tenantId,
            String sourceAgent,
            String category,
            String title,
            String content,
            String linkUrl,
            String bizType,
            String bizId,
            String dedupKey
    ) {
    }
}
