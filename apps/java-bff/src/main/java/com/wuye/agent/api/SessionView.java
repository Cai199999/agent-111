package com.wuye.agent.api;

import com.wuye.agent.store.SessionState;
import java.time.Instant;

public record SessionView(
        String sessionNo,
        String title,
        Instant createdAt,
        Instant lastMessageAt,
        int messageCount
) {
    public static SessionView from(SessionState session) {
        return new SessionView(session.sessionNo, session.title, session.createdAt,
                session.lastMessageAt, session.messageCount);
    }
}
