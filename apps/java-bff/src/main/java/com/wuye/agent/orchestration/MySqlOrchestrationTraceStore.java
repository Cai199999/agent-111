package com.wuye.agent.orchestration;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * MySQL 编排审计（mysql profile）：跨重启可回放，不存 input/output 原文。
 */
@Component
@Profile("mysql")
public class MySqlOrchestrationTraceStore implements OrchestrationTraceStore {

    private static final Logger log = LoggerFactory.getLogger(MySqlOrchestrationTraceStore.class);
    private static final TypeReference<List<String>> LIST_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public MySqlOrchestrationTraceStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public void append(OrchestrationTrace trace) {
        if (trace == null) {
            return;
        }
        Instant now = Instant.now();
        jdbc.update("""
                        INSERT INTO wa_orchestration_trace
                            (trace_id, session_no, phase, verdict, clarification,
                             capabilities_json, statuses_json, occurred_at, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                trace.traceId(),
                trace.sessionNo(),
                trace.phase() == null ? "UNKNOWN" : trace.phase(),
                trace.verdict() == null ? "UNKNOWN" : trace.verdict(),
                truncate(trace.clarification(), 1000),
                json(trace.capabilities()),
                json(trace.statuses()),
                ts(trace.occurredAt() == null ? now : trace.occurredAt()),
                ts(now));
    }

    @Override
    public List<OrchestrationTrace> recent(int limit) {
        List<OrchestrationTrace> rows = jdbc.query("""
                        SELECT trace_id, session_no, phase, verdict, clarification,
                               capabilities_json, statuses_json, occurred_at
                        FROM wa_orchestration_trace
                        ORDER BY occurred_at DESC, id DESC
                        LIMIT ?
                        """,
                (rs, rowNum) -> new OrchestrationTrace(
                        rs.getString("trace_id"),
                        rs.getString("session_no"),
                        rs.getString("phase"),
                        rs.getString("verdict"),
                        rs.getString("clarification"),
                        list(rs.getString("capabilities_json")),
                        list(rs.getString("statuses_json")),
                        instant(rs.getTimestamp("occurred_at"))),
                Math.max(1, limit));
        Collections.reverse(rows);
        return rows;
    }

    @Override
    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("backend", "MYSQL");
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM wa_orchestration_trace", Long.class);
        stats.put("size", total == null ? 0L : total);
        Map<String, Object> verdicts = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT verdict, COUNT(*) AS c FROM wa_orchestration_trace GROUP BY verdict")) {
            Object count = row.get("c");
            verdicts.put(String.valueOf(row.get("verdict")),
                    count instanceof Number number ? number.longValue() : 0L);
        }
        stats.put("verdicts", verdicts);
        return stats;
    }

    private String json(List<String> values) {
        try {
            return mapper.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception ex) {
            log.warn("orchestration trace serialization failed: {}", ex.getMessage());
            return "[]";
        }
    }

    private List<String> list(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, LIST_TYPE);
        } catch (Exception ex) {
            log.warn("orchestration trace parse failed: {}", ex.getMessage());
            return List.of();
        }
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? Instant.now() : timestamp.toInstant();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
