package com.wuye.agent.notification;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!mysql")
public class InMemoryNotificationStore implements NotificationStore {

    private final Map<String, NotificationRecord> byDedup = new LinkedHashMap<>();

    @Override
    public synchronized Optional<NotificationRecord> findByDedup(String dedupKey) {
        return Optional.ofNullable(byDedup.get(dedupKey));
    }

    @Override
    public synchronized NotificationRecord insertIfAbsent(NotificationRecord record) {
        NotificationRecord existing = byDedup.get(record.dedupKey());
        if (existing != null) {
            return existing;
        }
        byDedup.put(record.dedupKey(), record);
        return record;
    }

    @Override
    public synchronized List<NotificationRecord> list(String recipientActorId, String status,
                                                       int limit) {
        int size = Math.max(1, Math.min(limit, 100));
        List<NotificationRecord> result = new ArrayList<>();
        byDedup.values().stream()
                .filter(item -> item.recipientActorId().equals(recipientActorId))
                .filter(item -> status == null || status.isBlank() || item.status().equals(status))
                .sorted(Comparator.comparing(NotificationRecord::createdAt).reversed())
                .limit(size)
                .forEach(result::add);
        return result;
    }

    @Override
    public synchronized long unreadCount(String recipientActorId) {
        return byDedup.values().stream()
                .filter(item -> item.recipientActorId().equals(recipientActorId))
                .filter(item -> "UNREAD".equals(item.status()))
                .count();
    }

    @Override
    public synchronized boolean markRead(String recipientActorId, String notificationNo) {
        for (Map.Entry<String, NotificationRecord> entry : byDedup.entrySet()) {
            NotificationRecord item = entry.getValue();
            if (item.recipientActorId().equals(recipientActorId)
                    && item.notificationNo().equals(notificationNo)
                    && "UNREAD".equals(item.status())) {
                entry.setValue(item.read());
                return true;
            }
        }
        return false;
    }

    @Override
    public synchronized int markAllRead(String recipientActorId) {
        int changed = 0;
        for (Map.Entry<String, NotificationRecord> entry : byDedup.entrySet()) {
            NotificationRecord item = entry.getValue();
            if (item.recipientActorId().equals(recipientActorId)
                    && "UNREAD".equals(item.status())) {
                entry.setValue(item.read());
                changed++;
            }
        }
        return changed;
    }
}
