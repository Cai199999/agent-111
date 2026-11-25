package com.wuye.agent.notification;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("mysql")
public class MySqlNotificationStore implements NotificationStore {

    private final JdbcTemplate jdbc;

    public MySqlNotificationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<NotificationRecord> findByDedup(String dedupKey) {
        List<NotificationRecord> rows = jdbc.query(
                "SELECT * FROM wa_notification WHERE dedup_key = ?",
                (rs, rowNum) -> map(rs), dedupKey);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public NotificationRecord insertIfAbsent(NotificationRecord record) {
        Optional<NotificationRecord> existing = findByDedup(record.dedupKey());
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            jdbc.update("""
                            INSERT INTO wa_notification
                                (notification_no, recipient_actor_id, tenant_id, source_agent,
                                 category, title, content, link_url, biz_type, biz_id,
                                 dedup_key, status, created_at, read_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    record.notificationNo(), record.recipientActorId(), record.tenantId(),
                    record.sourceAgent(), record.category(), record.title(), record.content(),
                    record.linkUrl(), record.bizType(), record.bizId(), record.dedupKey(),
                    record.status(), Timestamp.from(record.createdAt()),
                    record.readAt() == null ? null : Timestamp.from(record.readAt()));
        } catch (DuplicateKeyException ex) {
            // 并发下另一个请求已写入同一 dedupKey，返回已有记录。
            return findByDedup(record.dedupKey()).orElse(record);
        }
        return record;
    }

    @Override
    public List<NotificationRecord> list(String recipientActorId, String status, int limit) {
        int size = Math.max(1, Math.min(limit, 100));
        boolean filtered = status != null && !status.isBlank();
        String sql = filtered
                ? "SELECT * FROM wa_notification WHERE recipient_actor_id = ? AND status = ? "
                  + "ORDER BY id DESC LIMIT ?"
                : "SELECT * FROM wa_notification WHERE recipient_actor_id = ? "
                  + "ORDER BY id DESC LIMIT ?";
        Object[] args = filtered
                ? new Object[]{recipientActorId, status, size}
                : new Object[]{recipientActorId, size};
        return jdbc.query(sql, (rs, rowNum) -> map(rs), args);
    }

    @Override
    public long unreadCount(String recipientActorId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM wa_notification WHERE recipient_actor_id = ? AND status = 'UNREAD'",
                Long.class, recipientActorId);
        return count == null ? 0 : count;
    }

    @Override
    public boolean markRead(String recipientActorId, String notificationNo) {
        int rows = jdbc.update("""
                        UPDATE wa_notification
                        SET status = 'READ', read_at = ?
                        WHERE notification_no = ? AND recipient_actor_id = ? AND status = 'UNREAD'
                        """,
                Timestamp.from(Instant.now()), notificationNo, recipientActorId);
        return rows > 0;
    }

    @Override
    public int markAllRead(String recipientActorId) {
        return jdbc.update("""
                        UPDATE wa_notification
                        SET status = 'READ', read_at = ?
                        WHERE recipient_actor_id = ? AND status = 'UNREAD'
                        """,
                Timestamp.from(Instant.now()), recipientActorId);
    }

    private NotificationRecord map(ResultSet rs) throws SQLException {
        Timestamp readAt = rs.getTimestamp("read_at");
        return new NotificationRecord(
                rs.getString("notification_no"),
                rs.getString("recipient_actor_id"),
                rs.getString("tenant_id"),
                rs.getString("source_agent"),
                rs.getString("category"),
                rs.getString("title"),
                rs.getString("content"),
                rs.getString("link_url"),
                rs.getString("biz_type"),
                rs.getString("biz_id"),
                rs.getString("dedup_key"),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(),
                readAt == null ? null : readAt.toInstant());
    }
}
