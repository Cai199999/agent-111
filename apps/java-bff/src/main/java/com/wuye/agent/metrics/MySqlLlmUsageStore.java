package com.wuye.agent.metrics;

import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("mysql")
public class MySqlLlmUsageStore implements LlmUsageStore {

    private final JdbcTemplate jdbc;

    public MySqlLlmUsageStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(LlmUsageEvent event) {
        jdbc.update("""
                        INSERT INTO wa_llm_usage
                            (operation, prompt_tokens, completion_tokens, total_tokens, cost_cny, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                event.operation(), event.promptTokens(), event.completionTokens(),
                event.totalTokens(), event.costCny(), Timestamp.from(event.createdAt()));
    }

    @Override
    public LlmUsageSummary summary(Instant from, Instant to) {
        return jdbc.query("""
                        SELECT COUNT(*) AS calls,
                               COALESCE(SUM(prompt_tokens),0) AS prompt_tokens,
                               COALESCE(SUM(completion_tokens),0) AS completion_tokens,
                               COALESCE(SUM(total_tokens),0) AS total_tokens,
                               COALESCE(SUM(cost_cny),0) AS cost_cny
                        FROM wa_llm_usage
                        WHERE created_at >= ? AND created_at <= ?
                        """,
                rs -> {
                    if (!rs.next()) {
                        return LlmUsageSummary.empty();
                    }
                    return new LlmUsageSummary(
                            rs.getLong("calls"),
                            rs.getLong("prompt_tokens"),
                            rs.getLong("completion_tokens"),
                            rs.getLong("total_tokens"),
                            rs.getDouble("cost_cny"));
                },
                Timestamp.from(from == null ? Instant.EPOCH : from),
                Timestamp.from(to == null ? Instant.now() : to));
    }
}
