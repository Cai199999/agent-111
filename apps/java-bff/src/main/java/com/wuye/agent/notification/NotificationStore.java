package com.wuye.agent.notification;

import java.util.List;
import java.util.Optional;

/**
 * 通知存储抽象：mysql 落表，其它 profile 用内存实现。
 */
public interface NotificationStore {

    Optional<NotificationRecord> findByDedup(String dedupKey);

    NotificationRecord insertIfAbsent(NotificationRecord record);

    List<NotificationRecord> list(String recipientActorId, String status, int limit);

    long unreadCount(String recipientActorId);

    boolean markRead(String recipientActorId, String notificationNo);

    int markAllRead(String recipientActorId);
}
