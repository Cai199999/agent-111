package com.wuye.agent.event;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 可靠事件出口：先写 Outbox，再投递到事件总线。
 *
 * <p>同步投递失败/进程中断时，定时 relay 会扫描 PENDING 继续投；
 * 消费端按 eventId 幂等，整体语义为至少一次。</p>
 */
@Component
@Primary
public class OutboxEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventPublisher.class);

    private final EventOutboxStore outbox;
    private final EventTransport transport;
    private final boolean enabled;

    public OutboxEventPublisher(EventOutboxStore outbox, EventTransport transport,
                                @Value("${wuye.event.outbox.enabled:true}") boolean enabled) {
        this.outbox = outbox;
        this.transport = transport;
        this.enabled = enabled;
    }

    @Override
    public void publish(EventEnvelope event) {
        if (event == null) {
            return;
        }
        if (!enabled) {
            transport.send(event);
            return;
        }
        try {
            outbox.append(event);
        } catch (Exception ex) {
            // 写 Outbox 失败不阻断任务；至少保证本次投递尝试继续。
            log.warn("event outbox append failed eventId={} type={} error={}",
                    event.eventId(), event.eventType(), ex.getMessage());
        }
        dispatch(event);
    }

    /**
     * 定时补发：覆盖“写库成功但进程在投递前中断”的场景。
     * 单机原型用 fixedDelay；多实例需加分布式锁，或改由 Kafka 消费位点接管。
     */
    @Scheduled(fixedDelayString = "${wuye.event.outbox.relay-ms:5000}")
    public void relay() {
        if (!enabled) {
            return;
        }
        try {
            for (EventEnvelope event : outbox.pending(50)) {
                dispatch(event);
            }
        } catch (Exception ex) {
            log.warn("event outbox relay failed: {}", ex.getMessage());
        }
    }

    public Map<String, Object> stats() {
        Map<String, Object> stats = new java.util.LinkedHashMap<>(outbox.stats());
        stats.put("enabled", enabled);
        return stats;
    }

    public java.util.List<EventEnvelope> recent(int limit) {
        return enabled ? outbox.recent(limit) : java.util.List.of();
    }

    private void dispatch(EventEnvelope event) {
        try {
            transport.send(event);
            outbox.markDispatched(event.eventId());
        } catch (Exception ex) {
            log.warn("event dispatch failed eventId={} type={} error={}",
                    event.eventId(), event.eventType(), ex.getMessage());
            try {
                outbox.markFailed(event.eventId(), ex.getMessage());
            } catch (Exception markEx) {
                log.warn("event outbox markFailed failed eventId={} error={}",
                        event.eventId(), markEx.getMessage());
            }
        }
    }
}
