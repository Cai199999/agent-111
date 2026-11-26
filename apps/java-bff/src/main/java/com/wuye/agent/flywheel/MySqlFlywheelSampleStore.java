package com.wuye.agent.flywheel;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * MySQL 飞轮样本存储（mysql profile）。
 *
 * <p>与 JSONL 后端接口一致；样本只落本项目 `wuye_agent_db`，不跨项目共享。</p>
 */
@Component
@Profile("mysql")
public class MySqlFlywheelSampleStore implements FlywheelSampleStore {

    private static final Logger log = LoggerFactory.getLogger(MySqlFlywheelSampleStore.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public MySqlFlywheelSampleStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public void append(FlywheelSample sample) {
        if (sample == null) {
            return;
        }
        try {
            jdbc.update("""
                            INSERT INTO wa_flywheel_sample
                                (sample_id, event_id, event_type, task_no, session_no, agent_key,
                                 capability_id, actor_id, tenant_id, status, input_json,
                                 output_json, error_json, duration_ms, occurred_at, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    sample.sampleId(),
                    sample.eventId(),
                    sample.eventType(),
                    sample.taskNo(),
                    sample.sessionNo(),
                    sample.agentKey(),
                    sample.capabilityId(),
                    sample.actorId(),
                    sample.tenantId(),
                    sample.status(),
                    json(sample.input()),
                    json(sample.output()),
                    json(sample.error()),
                    sample.durationMs(),
                    ts(sample.occurredAt()),
                    ts(Instant.now()));
        } catch (DuplicateKeyException ex) {
            // 同一 event_id 重复消费是幂等场景。
            log.debug("flywheel sample duplicate eventId={}", sample.eventId());
        }
    }

    @Override
    public List<FlywheelSample> recent(int limit) {
        List<FlywheelSample> rows = jdbc.query("""
                        SELECT sample_id, event_id, event_type, task_no, session_no, agent_key,
                               capability_id, actor_id, tenant_id, status, input_json,
                               output_json, error_json, duration_ms, occurred_at
                        FROM wa_flywheel_sample
                        ORDER BY occurred_at DESC
                        LIMIT ?
                        """,
                (rs, rowNum) -> new FlywheelSample(
                        rs.getString("sample_id"),
                        rs.getString("event_id"),
                        rs.getString("event_type"),
                        rs.getString("task_no"),
                        rs.getString("session_no"),
                        rs.getString("agent_key"),
                        rs.getString("capability_id"),
                        rs.getString("actor_id"),
                        rs.getString("tenant_id"),
                        rs.getString("status"),
                        map(rs.getString("input_json")),
                        object(rs.getString("output_json")),
                        map(rs.getString("error_json")),
                        rs.getLong("duration_ms"),
                        instant(rs.getTimestamp("occurred_at"))),
                Math.max(1, limit));
        // 接口约定时间升序；SQL 用 DESC 取最近 N 条，再反转。
        Collections.reverse(rows);
        return rows;
    }

    @Override
    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("backend", "MYSQL");
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM wa_flywheel_sample", Long.class);
        stats.put("total", total == null ? 0L : total);
        Map<String, Object> counts = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT status, COUNT(*) AS c FROM wa_flywheel_sample GROUP BY status")) {
            Object count = row.get("c");
            counts.put(String.valueOf(row.get("status")),
                    count instanceof Number number ? number.longValue() : 0L);
        }
        stats.put("counts", counts);
        return stats;
    }

    private String json(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map && map.isEmpty()) {
            return null;
        }
        if (value instanceof List<?> list && list.isEmpty()) {
            return null;
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ex) {
            log.warn("flywheel sample serialization failed: {}", ex.getMessage());
            return null;
        }
    }

    private Map<String, Object> map(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, MAP_TYPE);
        } catch (Exception ex) {
            log.warn("flywheel sample map parse failed: {}", ex.getMessage());
            return Map.of();
        }
    }

    private Object object(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, Object.class);
        } catch (Exception ex) {
            log.warn("flywheel sample object parse failed: {}", ex.getMessage());
            return null;
        }
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? Timestamp.from(Instant.now()) : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? Instant.now() : timestamp.toInstant();
    }
}
