package com.wuye.agent.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuye.agent.agent.model.AgentError;
import com.wuye.agent.agent.model.Confirmation;
import com.wuye.agent.agent.model.TaskEvent;
import com.wuye.agent.agent.model.TaskStatus;
import com.wuye.agent.common.Ids;
import com.wuye.agent.persistence.TokenCipher;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * MySQL 持久化实现（`mysql` profile）。
 *
 * <p>会话/消息/任务/事件落 MySQL；确认令牌用 AES-GCM 加密后存
 * `wa_task_binding.confirmation_token_cipher`，不落明文。生产可把令牌迁到 Redis TTL。</p>
 */
@Component
@Profile("mysql")
public class MySqlStore implements Store {

    private static final Logger log = LoggerFactory.getLogger(MySqlStore.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TokenCipher tokenCipher;
    private final Ids ids;

    public MySqlStore(JdbcTemplate jdbc, ObjectMapper mapper, TokenCipher tokenCipher, Ids ids) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.tokenCipher = tokenCipher;
        this.ids = ids;
    }

    @Override
    @Transactional
    public SessionState createSession(long userId, String title) {
        String sessionNo = ids.next("ws_");
        Instant now = Instant.now();
        String safeTitle = title == null || title.isBlank() ? "新会话" : title;
        jdbc.update("""
                INSERT INTO wa_chat_session
                    (session_no, user_id, tenant_id, title, summary, summary_updated_at,
                     message_count, created_at, last_message_at)
                VALUES (?, ?, '0', ?, NULL, NULL, 0, ?, ?)
                """, sessionNo, userId, safeTitle, ts(now), ts(now));
        return new SessionState(sessionNo, userId, safeTitle, now);
    }

    @Override
    public SessionState getSession(String sessionNo) {
        List<SessionState> rows = jdbc.query("""
                        SELECT session_no, user_id, title, summary, summary_updated_at,
                               message_count, created_at, last_message_at
                        FROM wa_chat_session WHERE session_no = ?
                        """,
                (rs, rowNum) -> mapSession(rs), sessionNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public List<SessionState> listSessions(long userId) {
        return jdbc.query("""
                        SELECT session_no, user_id, title, summary, summary_updated_at,
                               message_count, created_at, last_message_at
                        FROM wa_chat_session WHERE user_id = ? ORDER BY last_message_at DESC
                        """,
                (rs, rowNum) -> mapSession(rs), userId);
    }

    @Override
    public void updateSessionSummary(String sessionNo, String summary) {
        jdbc.update("""
                        UPDATE wa_chat_session
                        SET summary = ?, summary_updated_at = ?
                        WHERE session_no = ?
                        """,
                summary, ts(Instant.now()), sessionNo);
    }

    @Override
    @Transactional
    public boolean deleteSession(String sessionNo) {
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM wa_chat_session WHERE session_no = ?", Integer.class, sessionNo);
        if (exists == null || exists == 0) {
            return false;
        }
        // 级联删除：事件 -> 任务 -> 消息 -> 会话；确认令牌随任务行一起删除
        jdbc.update("""
                DELETE FROM wa_task_event
                WHERE task_no IN (SELECT task_no FROM wa_task_binding WHERE session_no = ?)
                """, sessionNo);
        jdbc.update("DELETE FROM wa_task_binding WHERE session_no = ?", sessionNo);
        jdbc.update("DELETE FROM wa_chat_message WHERE session_no = ?", sessionNo);
        jdbc.update("DELETE FROM wa_chat_session WHERE session_no = ?", sessionNo);
        return true;
    }

    @Override
    @Transactional
    public ChatMessage addMessage(SessionState session, String role, String content,
                                  String contentType, Map<String, Object> metadata,
                                  String clientMessageId) {
        Instant now = Instant.now();
        ChatMessage message = new ChatMessage(ids.next("wm_"), session.sessionNo, role, content,
                contentType, metadata == null ? Map.of() : metadata, now);
        jdbc.update("""
                        INSERT INTO wa_chat_message
                            (message_no, session_no, tenant_id, role, content, content_type,
                             metadata_json, client_message_id, created_at)
                        VALUES (?, ?, '0', ?, ?, ?, ?, ?, ?)
                        """,
                message.messageNo(), message.sessionNo(), role, content, contentType,
                json(message.metadata()), clientMessageId, ts(now));

        String titleCandidate = content == null ? "" : content.strip();
        if (titleCandidate.length() > 24) {
            titleCandidate = titleCandidate.substring(0, 24) + "…";
        }
        jdbc.update("""
                        UPDATE wa_chat_session
                        SET title = CASE
                                WHEN ? = 'USER' AND message_count = 0
                                     AND (title IS NULL OR title = '' OR title = '新会话')
                                THEN ? ELSE title END,
                            message_count = message_count + 1,
                            last_message_at = ?
                        WHERE session_no = ?
                        """,
                role, titleCandidate, ts(now), session.sessionNo);

        session.lastMessageAt = now;
        session.messageCount++;
        if ("USER".equals(role) && ("新会话".equals(session.title) || session.title == null || session.title.isBlank())) {
            session.title = titleCandidate;
        }
        return message;
    }

    @Override
    public List<ChatMessage> messages(String sessionNo) {
        return jdbc.query("""
                        SELECT message_no, session_no, role, content, content_type, metadata_json, created_at
                        FROM wa_chat_message WHERE session_no = ? ORDER BY created_at ASC
                        """,
                (rs, rowNum) -> mapMessage(rs), sessionNo);
    }

    @Override
    public ChatMessage findMessageByClientId(SessionState session, String clientMessageId) {
        if (clientMessageId == null || clientMessageId.isBlank()) {
            return null;
        }
        List<ChatMessage> rows = jdbc.query("""
                        SELECT message_no, session_no, role, content, content_type, metadata_json, created_at
                        FROM wa_chat_message
                        WHERE session_no = ? AND client_message_id = ? LIMIT 1
                        """,
                (rs, rowNum) -> mapMessage(rs), session.sessionNo, clientMessageId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    @Transactional
    public void putTask(TaskRecord task) {
        Confirmation confirmation = task.confirmation;
        String tokenCipher = null;
        if (task.status == TaskStatus.WAITING_CONFIRM && confirmation != null && confirmation.token() != null) {
            tokenCipher = encryptToken(confirmation.token());
        }
        jdbc.update("""
                        INSERT INTO wa_task_binding (
                            task_no, session_no, tenant_id, agent_key, capability_id, client_task_id,
                            actor_id, execution_mode, child_task_id, status,
                            input_json, output_json, error_json,
                            confirmation_status, confirmation_title, confirmation_risk,
                            confirmation_fields_json, confirmation_expires_at, confirmation_state,
                            confirmation_token_cipher, progress, current_step,
                            cancel_supported, cancel_reason, created_at, started_at, finished_at
                        ) VALUES (?, ?, '0', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                            child_task_id = VALUES(child_task_id),
                            status = VALUES(status),
                            input_json = VALUES(input_json),
                            output_json = VALUES(output_json),
                            error_json = VALUES(error_json),
                            confirmation_status = VALUES(confirmation_status),
                            confirmation_title = VALUES(confirmation_title),
                            confirmation_risk = VALUES(confirmation_risk),
                            confirmation_fields_json = VALUES(confirmation_fields_json),
                            confirmation_expires_at = VALUES(confirmation_expires_at),
                            confirmation_state = VALUES(confirmation_state),
                            confirmation_token_cipher = VALUES(confirmation_token_cipher),
                            progress = VALUES(progress),
                            current_step = VALUES(current_step),
                            cancel_supported = VALUES(cancel_supported),
                            cancel_reason = VALUES(cancel_reason),
                            started_at = VALUES(started_at),
                            finished_at = VALUES(finished_at)
                        """,
                task.taskNo, task.sessionNo, task.agentKey, task.capabilityId, task.clientTaskId,
                task.actorId, task.executionMode, task.childTaskId, task.status.name(),
                json(task.input), json(task.output), json(task.error),
                task.confirmationStatus,
                confirmation == null ? null : confirmation.title(),
                confirmation == null ? null : confirmation.riskLevel(),
                confirmation == null ? null : json(confirmation.fields()),
                confirmation == null ? null : ts(confirmation.expiresAt()),
                confirmation == null ? null : confirmation.status(),
                tokenCipher,
                task.progress, task.currentStep, task.cancelSupported ? 1 : 0,
                task.cancelReason, ts(task.createdAt), ts(task.startedAt), ts(task.finishedAt));

        for (TaskEvent event : task.events) {
            jdbc.update("""
                            INSERT IGNORE INTO wa_task_event
                                (task_no, seq, event_type, status, payload_json, created_at)
                            VALUES (?, ?, ?, ?, ?, ?)
                            """,
                    task.taskNo, event.seq(), event.type(),
                    event.status() == null ? null : event.status().name(),
                    json(event.payload()), ts(event.createdAt()));
        }
    }

    @Override
    public TaskRecord getTask(String taskNo) {
        List<TaskRecord> rows = jdbc.query("SELECT * FROM wa_task_binding WHERE task_no = ?",
                (rs, rowNum) -> mapTask(rs), taskNo);
        if (rows.isEmpty()) {
            return null;
        }
        TaskRecord task = rows.get(0);
        loadEvents(task);
        return task;
    }

    @Override
    public TaskRecord getTaskByClientId(String agentKey, String clientTaskId) {
        List<String> rows = jdbc.queryForList("""
                        SELECT task_no FROM wa_task_binding
                        WHERE agent_key = ? AND client_task_id = ? LIMIT 1
                        """, String.class, agentKey, clientTaskId);
        return rows.isEmpty() ? null : getTask(rows.get(0));
    }

    @Override
    public List<TaskRecord> listTasks(String sessionNo) {
        List<String> taskNos = sessionNo == null || sessionNo.isBlank()
                ? jdbc.queryForList("SELECT task_no FROM wa_task_binding ORDER BY created_at DESC", String.class)
                : jdbc.queryForList("SELECT task_no FROM wa_task_binding WHERE session_no = ? ORDER BY created_at DESC",
                String.class, sessionNo);
        List<TaskRecord> tasks = new ArrayList<>(taskNos.size());
        for (String taskNo : taskNos) {
            TaskRecord task = getTask(taskNo);
            if (task != null) {
                tasks.add(task);
            }
        }
        return tasks;
    }

    @Override
    public void putConfirmationToken(String token, TaskRecord task) {
        // 任务保存时已把令牌加密写入 wa_task_binding，这里无需重复写
    }

    @Override
    public TaskRecord getConfirmationToken(String token) {
        // TaskService 从 TaskRecord.confirmation 取令牌；本方法保留接口兼容
        return null;
    }

    @Override
    public void removeConfirmationToken(String token) {
        // 任务进入终态后 putTask 会把 cipher 置空；这里保留接口兼容
    }

    private SessionState mapSession(ResultSet rs) throws SQLException {
        SessionState session = new SessionState(
                rs.getString("session_no"),
                rs.getLong("user_id"),
                rs.getString("title"),
                instant(rs.getTimestamp("created_at")));
        session.messageCount = rs.getInt("message_count");
        session.summary = rs.getString("summary");
        session.summaryUpdatedAt = instant(rs.getTimestamp("summary_updated_at"));
        session.lastMessageAt = instant(rs.getTimestamp("last_message_at"));
        return session;
    }

    private ChatMessage mapMessage(ResultSet rs) throws SQLException {
        return new ChatMessage(
                rs.getString("message_no"),
                rs.getString("session_no"),
                rs.getString("role"),
                rs.getString("content"),
                rs.getString("content_type"),
                readMap(rs.getString("metadata_json")),
                instant(rs.getTimestamp("created_at")));
    }

    private TaskRecord mapTask(ResultSet rs) throws SQLException {
        TaskRecord task = new TaskRecord(
                rs.getString("task_no"),
                rs.getString("session_no"),
                rs.getString("agent_key"),
                rs.getString("capability_id"),
                rs.getString("client_task_id"),
                rs.getString("actor_id"),
                rs.getString("execution_mode"),
                readMap(rs.getString("input_json")),
                instant(rs.getTimestamp("created_at")));
        task.childTaskId = rs.getString("child_task_id");
        task.status = TaskStatus.valueOf(rs.getString("status"));
        task.output = readObject(rs.getString("output_json"));
        task.error = readError(rs.getString("error_json"));
        task.confirmationStatus = rs.getString("confirmation_status");
        task.progress = rs.getInt("progress");
        task.currentStep = rs.getString("current_step");
        task.cancelSupported = rs.getBoolean("cancel_supported");
        task.cancelReason = rs.getString("cancel_reason");
        task.startedAt = instant(rs.getTimestamp("started_at"));
        task.finishedAt = instant(rs.getTimestamp("finished_at"));

        String title = rs.getString("confirmation_title");
        if (title != null) {
            String token = null;
            String cipherText = rs.getString("confirmation_token_cipher");
            if (task.status == TaskStatus.WAITING_CONFIRM && cipherText != null) {
                try {
                    token = tokenCipher.decrypt(cipherText);
                } catch (Exception ex) {
                    log.warn("确认令牌解密失败 taskNo={}，需要重新发起任务", task.taskNo, ex);
                }
            }
            task.confirmation = new Confirmation(
                    token,
                    title,
                    rs.getString("confirmation_risk"),
                    readFields(rs.getString("confirmation_fields_json")),
                    instant(rs.getTimestamp("confirmation_expires_at")),
                    rs.getString("confirmation_state"));
        }
        return task;
    }

    private void loadEvents(TaskRecord task) {
        List<TaskEvent> events = jdbc.query("""
                        SELECT seq, event_type, status, payload_json, created_at
                        FROM wa_task_event WHERE task_no = ? ORDER BY seq ASC
                        """,
                (rs, rowNum) -> new TaskEvent(
                        rs.getLong("seq"),
                        rs.getString("event_type"),
                        rs.getString("status") == null ? null : TaskStatus.valueOf(rs.getString("status")),
                        readMap(rs.getString("payload_json")),
                        instant(rs.getTimestamp("created_at"))),
                task.taskNo);
        long maxSeq = 0;
        long maxChildSeq = 0;
        for (TaskEvent event : events) {
            task.events.add(event);
            maxSeq = Math.max(maxSeq, event.seq());
            Object childSeq = event.payload().get("childSeq");
            if (childSeq instanceof Number number) {
                maxChildSeq = Math.max(maxChildSeq, number.longValue());
            }
        }
        task.eventSeq.set(maxSeq);
        task.childEventSeq.set(maxChildSeq);
    }

    private String json(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("JSON 序列化失败", ex);
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
            log.warn("JSON 反序列化失败，返回空 Map", ex);
            return Map.of();
        }
    }

    private Object readObject(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, Object.class);
        } catch (Exception ex) {
            log.warn("JSON 反序列化失败，返回 null", ex);
            return null;
        }
    }

    private AgentError readError(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        Map<String, Object> map = readMap(json);
        return new AgentError(
                string(map, "code"),
                string(map, "category"),
                Boolean.TRUE.equals(map.get("retryable")),
                string(map, "message"));
    }

    private List<Confirmation.Field> readFields(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> raw = mapper.readValue(json,
                    new TypeReference<List<Map<String, Object>>>() {
                    });
            List<Confirmation.Field> fields = new ArrayList<>(raw.size());
            for (Map<String, Object> item : raw) {
                fields.add(new Confirmation.Field(string(item, "label"), string(item, "value")));
            }
            return fields;
        } catch (Exception ex) {
            log.warn("确认字段反序列化失败", ex);
            return List.of();
        }
    }

    private String encryptToken(String token) {
        return tokenCipher.encrypt(token);
    }

    private static String string(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
