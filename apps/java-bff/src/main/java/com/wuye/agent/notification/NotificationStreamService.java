package com.wuye.agent.notification;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 按 actorId 维护 SSE 连接；写入通知时实时推送，前端断线后重连。
 */
@Service
public class NotificationStreamService {

    private static final Logger log = LoggerFactory.getLogger(NotificationStreamService.class);
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    private final Map<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String actorId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        emitters.computeIfAbsent(actorId, key -> new CopyOnWriteArrayList<>()).add(emitter);
        Runnable cleanup = () -> remove(actorId, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error -> cleanup.run());
        try {
            emitter.send(SseEmitter.event().name("ready")
                    .data(Map.of("actorId", actorId), MediaType.APPLICATION_JSON));
        } catch (IOException ex) {
            cleanup.run();
        }
        return emitter;
    }

    public void publish(NotificationRecord record) {
        List<SseEmitter> targets = emitters.get(record.recipientActorId());
        if (targets == null || targets.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : targets) {
            try {
                emitter.send(SseEmitter.event().name("notification")
                        .data(record, MediaType.APPLICATION_JSON));
            } catch (Exception ex) {
                log.debug("通知 SSE 推送失败 actor={}：{}", record.recipientActorId(), ex.getMessage());
                remove(record.recipientActorId(), emitter);
            }
        }
    }

    private void remove(String actorId, SseEmitter emitter) {
        List<SseEmitter> targets = emitters.get(actorId);
        if (targets != null) {
            targets.remove(emitter);
            if (targets.isEmpty()) {
                emitters.remove(actorId);
            }
        }
    }
}
