package com.wuye.agent.memory;

import com.wuye.agent.routing.LlmRouterClient;
import com.wuye.agent.store.ChatMessage;
import com.wuye.agent.store.SessionState;
import com.wuye.agent.store.Store;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 会话级记忆 MVP：异步生成当前会话摘要，只用于当前会话上下文，
 * 不做跨会话检索，也不写入全局知识库。
 */
@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    private final Store store;
    private final LlmRouterClient llmRouterClient;
    private final UserProfileService userProfileService;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "memory-summary");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    public MemoryService(Store store, LlmRouterClient llmRouterClient,
                         UserProfileService userProfileService) {
        this.store = store;
        this.llmRouterClient = llmRouterClient;
        this.userProfileService = userProfileService;
    }

    public void updateAsync(String sessionNo) {
        if (sessionNo == null || !running.add(sessionNo)) {
            return;
        }
        executor.submit(() -> {
            try {
                SessionState session = store.getSession(sessionNo);
                if (session == null) {
                    return;
                }
                List<ChatMessage> messages = store.messages(sessionNo);
                if (messages.size() < 2) {
                    return;
                }
                List<Map<String, String>> turns = messages
                        .subList(Math.max(0, messages.size() - 12), messages.size())
                        .stream()
                        .map(message -> {
                            Map<String, String> turn = new LinkedHashMap<>();
                            turn.put("role", message.role());
                            turn.put("content", message.content() == null ? "" : message.content());
                            return turn;
                        })
                        .toList();
                llmRouterClient.summarize(sessionNo, session.summary, turns)
                        .filter(summary -> !summary.isBlank())
                        .ifPresent(summary -> {
                            store.updateSessionSummary(sessionNo, summary);
                            userProfileService.updateFromSession(session.userId, sessionNo, summary, messages);
                        });
            } catch (Exception ex) {
                log.warn("会话摘要生成失败 sessionNo={}：{}", sessionNo, ex.getMessage());
            } finally {
                running.remove(sessionNo);
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            executor.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
