package com.wuye.agent.audit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("mysql")
public class MySqlAuditStore implements AuditStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public MySqlAuditStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public void record(AuditEvent event) {
        jdbc.update("""
                        INSERT INTO wa_audit_log
                            (trace_id, user_id, username, action, resource, resource_id,
                             agent_key, capability_id, status, detail_json, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                event.traceId(), event.userId(), event.username(), event.action(),
                event.resource(), event.resourceId(), event.agentKey(), event.capabilityId(),
                event.status(), json(event.detail()), Timestamp.from(event.createdAt()));
    }

    @Override
    public List<AuditEvent> recent(int limit, String action) {
        int size = Math.max(1, Math.min(limit, 200));
        String sql = action == null || action.isBlank()
                ? "SELECT * FROM wa_audit_log ORDER BY created_at DESC LIMIT ?"
                : "SELECT * FROM wa_audit_log WHERE action = ? ORDER BY created_at DESC LIMIT ?";
        Object[] args = action == null || action.isBlank() ? new Object[]{size} : new Object[]{action, size};
        return jdbc.query(sql, (rs, rowNum) -> new AuditEvent(
                rs.getString("trace_id"),
                rs.getObject("user_id") == null ? null : rs.getLong("user_id"),
                rs.getString("username"),
                rs.getString("action"),
                rs.getString("resource"),
                rs.getString("resource_id"),
                rs.getString("agent_key"),
                rs.getString("capability_id"),
                rs.getString("status"),
                readMap(rs.getString("detail_json")),
                rs.getTimestamp("created_at").toInstant()), args);
    }

    private String json(Map<String, Object> detail) {
        if (detail == null || detail.isEmpty()) {
            return null;
        }
        try {
            return mapper.writeValueAsString(detail);
        } catch (Exception ex) {
            return null;
        }
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception ex) {
            return Map.of();
        }
    }
}
