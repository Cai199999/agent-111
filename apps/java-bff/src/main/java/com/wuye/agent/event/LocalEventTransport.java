package com.wuye.agent.event;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 默认传输：Outbox → 进程内事件总线。
 */
@Component
@ConditionalOnProperty(name = "wuye.event.backend", havingValue = "memory", matchIfMissing = true)
public class LocalEventTransport implements EventTransport {

    private final EventBus eventBus;

    public LocalEventTransport(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    @Override
    public void send(EventEnvelope event) {
        eventBus.publish(event);
    }
}
