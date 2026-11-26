package com.wuye.agent.event;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * MySQL Outbox（mysql profile）。
 *
 * <p>任务事件先写 `wa_event_outbox` 再投递；进程重启后 relay 扫描 PENDING 继续发，
 * 消费端按 eventId 幂等。表只存本项目事件，不跨项目共享。</p>
 */
@Component
@Profile("mysql")
public class MySqlEventOutboxStore implements EventOutboxStore {

    private static final Logger log = LoggerFactory.getLogger(MySqlEventOutboxStore.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final String SELECT_COLUMNS = """
            event_id, event_type, event_version, occurred_at, source, trace_id,
            actor_id, tenant_id, subject, idempotency_key, payload_json
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final int maxAttempts;

    public MySqlEventOutboxStore(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            @Value("${wuye.event.outbox.max-attempts:5}") int maxAttempts) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    @Override
    public void append(EventEnvelope event) {
        if (event == null) {
            return;
        }
        Instant now = Instant.now();
        try {
            jdbc.update("""
                            INSERT INTO wa_event_outbox
                                (event_id, event_type, event_version, occurred_at, source, trace_id,
                                 actor_id, tenant_id, subject, idempotency_key, payload_json,
                                 status, attempts, last_error, created_at, dispatched_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, NULL, ?, NULL)
                            """,
                    event.eventId(),
                    event.eventType(),
                    event.eventVersion(),
                    ts(event.occurredAt() == null ? now : event.occurredAt()),
                    event.source(),
                    event.traceId(),
                    event.actorId(),
                    event.tenantId(),
                    event.subject(),
                    event.idempotencyKey(),
                    payloadJson(event.payload()),
                    ts(now));
        } catch (DuplicateKeyException ex) {
            // 同一 eventId 重复写入是幂等场景，不视为故障。
            log.debug("event outbox duplicate eventId={}", event.eventId());
        }
    }

    @Override
    public List<EventEnvelope> pending(int limit) {
        return jdbc.query("""
                        SELECT %s
                        FROM wa_event_outbox
                        WHERE status = 'PENDING'
                        ORDER BY created_at
                        LIMIT ?
                        """.formatted(SELECT_COLUMNS),
                (rs, rowNum) -> mapEvent(rs),
                Math.max(1, limit));
    }

    @Override
    public List<EventEnvelope> recent(int limit) {
        List<EventEnvelope> rows = jdbc.query("""
                        SELECT %s
                        FROM wa_event_outbox
                        ORDER BY created_at DESC
                        LIMIT ?
                        """.formatted(SELECT_COLUMNS),
                (rs, rowNum) -> mapEvent(rs),
                Math.max(1, limit));
        // 接口约定时间升序；SQL 用 DESC 取最近 N 条，再反转。
        Collections.reverse(rows);
        return rows;
    }

    private EventEnvelope mapEvent(ResultSet rs) throws SQLException {
        return new EventEnvelope(
                rs.getString("event_id"),
                rs.getString("event_type"),
                rs.getInt("event_version"),
                instant(rs.getTimestamp("occurred_at")),
                rs.getString("source"),
                rs.getString("trace_id"),
                rs.getString("actor_id"),
                rs.getString("tenant_id"),
                rs.getString("subject"),
                rs.getString("idempotency_key"),
                payload(rs.getString("payload_json")));
    }

    @Override
    public void markDispatched(String eventId) {
        if (eventId == null) {
            return;
        }
        jdbc.update("""
                        UPDATE wa_event_outbox
                        SET status = 'DISPATCHED', attempts = attempts + 1,
                            last_error = NULL, dispatched_at = ?
                        WHERE event_id = ?
                        """,
                ts(Instant.now()), eventId);
    }

    @Override
    public void markFailed(String eventId, String error) {
        if (eventId == null) {
            return;
        }
        jdbc.update("""
                        UPDATE wa_event_outbox
                        SET attempts = attempts + 1,
                            last_error = ?,
                            status = CASE WHEN attempts + 1 >= ? THEN 'FAILED' ELSE 'PENDING' END
                        WHERE event_id = ?
                        """,
                truncate(error), maxAttempts, eventId);
    }

    @Override
    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("backend", "MYSQL");
        Map<String, Object> counts = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT status, COUNT(*) AS c FROM wa_event_outbox GROUP BY status")) {
            Object count = row.get("c");
            counts.put(String.valueOf(row.get("status")),
                    count instanceof Number number ? number.longValue() : 0L);
        }
        stats.put("counts", counts);
        stats.put("maxAttempts", maxAttempts);
        return stats;
    }

    private String payloadJson(Map<String, Object> payload) {
        try {
            return mapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (Exception ex) {
            log.warn("event payload serialization failed, store empty payload: {}", ex.getMessage());
            return "{}";
        }
    }

    private Map<String, Object> payload(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, MAP_TYPE);
        } catch (Exception ex) {
            log.warn("event payload deserialization failed: {}", ex.getMessage());
            return Map.of();
        }
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? Instant.now() : timestamp.toInstant();
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 500 ? error : error.substring(0, 500);
    }
}
