package com.wuye.agent.store;

import com.wuye.agent.common.Ids;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 原型内存存储：会话、消息、任务、确认令牌。
 *
 * <p>所有跨用户查询都只在单进程内生效；生产化由 MySQL/Redis 替换，
 * 但接口形态保持不变，控制器不感知存储实现。</p>
 */
@Component
@Profile("!mysql")
public class InMemoryStore implements Store {

    private final Ids ids;
    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();
    private final Map<String, TaskRecord> tasks = new ConcurrentHashMap<>();
    private final Map<String, TaskRecord> tasksByClientId = new ConcurrentHashMap<>();
    private final Map<String, TaskRecord> confirmationTokens = new ConcurrentHashMap<>();

    public InMemoryStore(Ids ids) {
        this.ids = ids;
    }

    public SessionState createSession(String title) {
        String sessionNo = ids.next("ws_");
        SessionState session = new SessionState(sessionNo, title == null ? "新会话" : title, Instant.now());
        sessions.put(sessionNo, session);
        return session;
    }

    public SessionState getSession(String sessionNo) {
        return sessions.get(sessionNo);
    }

    public List<SessionState> listSessions() {
        return sessions.values().stream()
                .sorted(Comparator.comparing((SessionState s) -> s.lastMessageAt).reversed())
                .toList();
    }

    public ChatMessage addMessage(SessionState session, String role, String content,
                                  String contentType, Map<String, Object> metadata,
                                  String clientMessageId) {
        ChatMessage message = new ChatMessage(
                ids.next("wm_"), session.sessionNo, role, content, contentType,
                metadata == null ? Map.of() : metadata, Instant.now());
        session.messages.add(message);
        session.lastMessageAt = message.createdAt();
        session.messageCount++;
        if (clientMessageId != null && !clientMessageId.isBlank()) {
            session.clientMessageIds.add(clientMessageId);
        }
        if ("USER".equals(role) && (session.title == null || session.title.isBlank() || "新会话".equals(session.title))) {
            String text = content == null ? "" : content.strip();
            session.title = text.length() > 24 ? text.substring(0, 24) + "…" : text;
        }
        return message;
    }

    public List<ChatMessage> messages(String sessionNo) {
        SessionState session = sessions.get(sessionNo);
        return session == null ? List.of() : List.copyOf(session.messages);
    }

    public ChatMessage findMessageByClientId(SessionState session, String clientMessageId) {
        if (clientMessageId == null || clientMessageId.isBlank()) {
            return null;
        }
        return session.messages.stream()
                .filter(m -> clientMessageId.equals(m.metadata().get("clientMessageId")))
                .findFirst()
                .orElse(null);
    }

    public void putTask(TaskRecord task) {
        tasks.put(task.taskNo, task);
        tasksByClientId.put(task.agentKey + "|" + task.clientTaskId, task);
    }

    public TaskRecord getTask(String taskNo) {
        return tasks.get(taskNo);
    }

    public TaskRecord getTaskByClientId(String agentKey, String clientTaskId) {
        return tasksByClientId.get(agentKey + "|" + clientTaskId);
    }

    public List<TaskRecord> listTasks(String sessionNo) {
        return tasks.values().stream()
                .filter(t -> sessionNo == null || sessionNo.equals(t.sessionNo))
                .sorted(Comparator.comparing((TaskRecord t) -> t.createdAt).reversed())
                .toList();
    }

    public void putConfirmationToken(String token, TaskRecord task) {
        if (token != null && !token.isBlank()) {
            confirmationTokens.put(token, task);
        }
    }

    public TaskRecord getConfirmationToken(String token) {
        return confirmationTokens.get(token);
    }

    public void removeConfirmationToken(String token) {
        if (token != null) {
            confirmationTokens.remove(token);
        }
    }
}
