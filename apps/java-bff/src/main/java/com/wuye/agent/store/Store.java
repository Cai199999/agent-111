package com.wuye.agent.store;

import java.util.List;
import java.util.Map;

/**
 * 主 Agent 自身数据存储抽象。
 *
 * <p>默认 `memory` profile 用 InMemoryStore；`mysql` profile 用 MySqlStore。
 * 业务层只依赖本接口，切换存储不改变 ChatService/TaskService。</p>
 */
public interface Store {

    SessionState createSession(String title);

    SessionState getSession(String sessionNo);

    List<SessionState> listSessions();

    ChatMessage addMessage(SessionState session, String role, String content,
                           String contentType, Map<String, Object> metadata,
                           String clientMessageId);

    List<ChatMessage> messages(String sessionNo);

    ChatMessage findMessageByClientId(SessionState session, String clientMessageId);

    void putTask(TaskRecord task);

    TaskRecord getTask(String taskNo);

    TaskRecord getTaskByClientId(String agentKey, String clientTaskId);

    List<TaskRecord> listTasks(String sessionNo);

    /**
     * 兼容旧接口：确认令牌实际保存在任务记录的 confirmation 中，
     * MySQL 实现会加密后写入任务表；内存实现保留 map。
     */
    void putConfirmationToken(String token, TaskRecord task);

    TaskRecord getConfirmationToken(String token);

    void removeConfirmationToken(String token);
}
