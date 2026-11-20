package com.wuye.agent.store;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 原型会话状态。生产化替换为 wa_chat_session / wa_chat_message。
 */
public final class SessionState {

    public final String sessionNo;
    public final Instant createdAt;
    public final List<ChatMessage> messages = new CopyOnWriteArrayList<>();
    public final Set<String> clientMessageIds = ConcurrentHashMap.newKeySet();
    public volatile String title;
    public volatile Instant lastMessageAt;
    public volatile int messageCount;

    public SessionState(String sessionNo, String title, Instant createdAt) {
        this.sessionNo = sessionNo;
        this.title = title;
        this.createdAt = createdAt;
        this.lastMessageAt = createdAt;
    }
}
