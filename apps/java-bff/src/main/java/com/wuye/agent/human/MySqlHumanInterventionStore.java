package com.wuye.agent.human;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("mysql")
public class MySqlHumanInterventionStore implements HumanInterventionStore {

    private final JdbcTemplate jdbc;

    public MySqlHumanInterventionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void upsertPending(String taskNo) {
        Instant now = Instant.now();
        jdbc.update("""
                        INSERT INTO wa_human_intervention
                            (task_no, status, assignee_id, assignee_name, note, created_at, updated_at)
                        VALUES (?, 'PENDING', NULL, NULL, NULL, ?, ?)
                        ON DUPLICATE KEY UPDATE
                            status = IF(status = 'RESOLVED', status, 'PENDING'),
                            updated_at = VALUES(updated_at)
                        """,
                taskNo, Timestamp.from(now), Timestamp.from(now));
    }

    @Override
    public Optional<HumanTaskRecord> get(String taskNo) {
        List<HumanTaskRecord> rows = jdbc.query(
                "SELECT * FROM wa_human_intervention WHERE task_no = ?",
                (rs, rowNum) -> map(rs), taskNo);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public List<HumanTaskRecord> list(String status, int limit) {
        int size = Math.max(1, Math.min(limit, 200));
        String sql = status == null || status.isBlank()
                ? "SELECT * FROM wa_human_intervention ORDER BY updated_at DESC LIMIT ?"
                : "SELECT * FROM wa_human_intervention WHERE status = ? ORDER BY updated_at DESC LIMIT ?";
        Object[] args = status == null || status.isBlank() ? new Object[]{size} : new Object[]{status, size};
        return jdbc.query(sql, (rs, rowNum) -> map(rs), args);
    }

    @Override
    public void claim(String taskNo, long userId, String username) {
        jdbc.update("""
                        UPDATE wa_human_intervention
                        SET status='CLAIMED', assignee_id=?, assignee_name=?, updated_at=?
                        WHERE task_no=? AND status <> 'RESOLVED'
                        """,
                userId, username, Timestamp.from(Instant.now()), taskNo);
    }

    @Override
    public void resolve(String taskNo, String note) {
        jdbc.update("""
                        UPDATE wa_human_intervention
                        SET status='RESOLVED', note=?, updated_at=?
                        WHERE task_no=?
                        """,
                note, Timestamp.from(Instant.now()), taskNo);
    }

    @Override
    public void autoResolve(String taskNo, String note) {
        jdbc.update("""
                        UPDATE wa_human_intervention
                        SET status='RESOLVED', note=?, updated_at=?
                        WHERE task_no=? AND status <> 'RESOLVED'
                        """,
                note, Timestamp.from(Instant.now()), taskNo);
    }

    private HumanTaskRecord map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new HumanTaskRecord(
                rs.getString("task_no"),
                rs.getString("status"),
                rs.getObject("assignee_id") == null ? null : rs.getLong("assignee_id"),
                rs.getString("assignee_name"),
                rs.getString("note"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
