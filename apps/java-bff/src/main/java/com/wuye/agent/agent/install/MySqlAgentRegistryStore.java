package com.wuye.agent.agent.install;

import com.wuye.agent.persistence.TokenCipher;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * MySQL 注册表：mysql profile 使用。
 *
 * <p>内部令牌使用 {@link TokenCipher}（AES-256-GCM）加密后再落库；
 * 单条记录损坏/解密失败时跳过并告警，不阻塞其他 Agent 启动。</p>
 */
@Component
@Profile("mysql")
public class MySqlAgentRegistryStore implements AgentRegistryStore {

    private static final Logger log = LoggerFactory.getLogger(MySqlAgentRegistryStore.class);

    private final JdbcTemplate jdbc;
    private final TokenCipher cipher;

    public MySqlAgentRegistryStore(JdbcTemplate jdbc, TokenCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    @Override
    public void save(AgentRegistryEntry entry) {
        jdbc.update("""
                        INSERT INTO wa_agent_registry
                            (agent_key, display_name, contract_version, dialect, kind, template_key,
                             descriptor_json, base_url, internal_token_cipher, audience, actor_id,
                             roles, tenant_id, use_delegation, health_path, ready_path,
                             manifest_path, status, installed_by, installed_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                            display_name = VALUES(display_name),
                            contract_version = VALUES(contract_version),
                            dialect = VALUES(dialect),
                            kind = VALUES(kind),
                            template_key = VALUES(template_key),
                            descriptor_json = VALUES(descriptor_json),
                            base_url = VALUES(base_url),
                            internal_token_cipher = VALUES(internal_token_cipher),
                            audience = VALUES(audience),
                            actor_id = VALUES(actor_id),
                            roles = VALUES(roles),
                            tenant_id = VALUES(tenant_id),
                            use_delegation = VALUES(use_delegation),
                            health_path = VALUES(health_path),
                            ready_path = VALUES(ready_path),
                            manifest_path = VALUES(manifest_path),
                            status = VALUES(status),
                            installed_by = VALUES(installed_by),
                            installed_at = VALUES(installed_at),
                            updated_at = VALUES(updated_at)
                        """,
                entry.agentKey(),
                entry.displayName(),
                entry.contractVersion(),
                entry.dialect(),
                entry.kind(),
                entry.templateKey(),
                entry.descriptorJson(),
                entry.baseUrl(),
                cipher.encrypt(entry.internalToken()),
                entry.audience(),
                entry.actorId(),
                entry.roles(),
                entry.tenantId(),
                entry.useDelegation() ? 1 : 0,
                entry.healthPath(),
                entry.readyPath(),
                entry.manifestPath(),
                entry.status(),
                entry.installedBy(),
                Timestamp.from(entry.installedAt()),
                Timestamp.from(entry.updatedAt()));
    }

    @Override
    public Optional<AgentRegistryEntry> find(String agentKey) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM wa_agent_registry WHERE agent_key = ?", agentKey);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(map(rows.get(0)));
    }

    @Override
    public List<AgentRegistryEntry> listActive() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM wa_agent_registry WHERE status = 'ACTIVE' ORDER BY id");
        List<AgentRegistryEntry> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            try {
                result.add(map(row));
            } catch (Exception ex) {
                log.warn("跳过损坏的 Agent 注册记录 agent={} error={}",
                        row.get("agent_key"), ex.getMessage());
            }
        }
        return result;
    }

    @Override
    public void remove(String agentKey) {
        jdbc.update("UPDATE wa_agent_registry SET status = 'REMOVED', updated_at = ? "
                + "WHERE agent_key = ?", Timestamp.from(Instant.now()), agentKey);
    }

    private AgentRegistryEntry map(Map<String, Object> row) {
        return new AgentRegistryEntry(
                text(row.get("agent_key")),
                text(row.get("display_name")),
                text(row.get("contract_version")),
                text(row.get("dialect")),
                text(row.get("kind")),
                text(row.get("template_key")),
                text(row.get("descriptor_json")),
                text(row.get("base_url")),
                decrypt(text(row.get("internal_token_cipher"))),
                text(row.get("audience")),
                text(row.get("actor_id")),
                text(row.get("roles")),
                text(row.get("tenant_id")),
                bool(row.get("use_delegation")),
                text(row.get("health_path")),
                text(row.get("ready_path")),
                text(row.get("manifest_path")),
                text(row.get("status")),
                text(row.get("installed_by")),
                instant(row.get("installed_at")),
                instant(row.get("updated_at")));
    }

    private String decrypt(String cipherText) {
        return cipherText == null || cipherText.isBlank() ? null : cipher.decrypt(cipherText);
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.intValue() != 0;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private static Instant instant(Object value) {
        if (value instanceof Timestamp ts) {
            return ts.toInstant();
        }
        if (value instanceof LocalDateTime ldt) {
            return ldt.atZone(java.time.ZoneId.systemDefault()).toInstant();
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        return Instant.now();
    }
}
