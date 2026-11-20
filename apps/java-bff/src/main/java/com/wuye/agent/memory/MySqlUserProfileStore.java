package com.wuye.agent.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("mysql")
public class MySqlUserProfileStore implements UserProfileStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public MySqlUserProfileStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public Optional<UserMemoryProfile> get(long userId) {
        List<UserMemoryProfile> rows = jdbc.query(
                "SELECT * FROM wa_user_memory_profile WHERE user_id = ?",
                (rs, rowNum) -> new UserMemoryProfile(
                        rs.getLong("user_id"),
                        readMap(rs.getString("profile_json")),
                        rs.getDouble("confidence"),
                        rs.getString("source_session_no"),
                        rs.getTimestamp("updated_at").toInstant()),
                userId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public void save(UserMemoryProfile profile) {
        jdbc.update("""
                        INSERT INTO wa_user_memory_profile
                            (user_id, profile_json, confidence, source_session_no, updated_at)
                        VALUES (?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                            profile_json = VALUES(profile_json),
                            confidence = VALUES(confidence),
                            source_session_no = VALUES(source_session_no),
                            updated_at = VALUES(updated_at)
                        """,
                profile.userId(), json(profile.profile()), profile.confidence(),
                profile.sourceSessionNo(), Timestamp.from(profile.updatedAt()));
    }

    @Override
    public void delete(long userId) {
        jdbc.update("DELETE FROM wa_user_memory_profile WHERE user_id = ?", userId);
    }

    private String json(Map<String, Object> profile) {
        try {
            return mapper.writeValueAsString(profile);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private Map<String, Object> readMap(String json) {
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception ex) {
            return Map.of();
        }
    }
}
