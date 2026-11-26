package com.wuye.agent.event;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 进程内事件总线：有界环形缓冲 + 同步扇出。
 *
 * <p>定位：第一阶段的本地实现，先冻结事件契约与消费者接口。
 * 生产多实例需要替换为 Outbox + Kafka（或 Redis Stream），本类不负责跨进程投递。</p>
 *
 * <p>隔离原则：</p>
 * <ul>
 *   <li>缓冲区只保留最近 N 条，内存占用可控；</li>
 *   <li>任一消费者抛异常只计数并记录，不影响其他消费者；</li>
 *   <li>publish 永不向任务主流程抛异常。</li>
 * </ul>
 */
@Component
public class InMemoryEventBus implements EventBus {

    private static final Logger log = LoggerFactory.getLogger(InMemoryEventBus.class);

    private final boolean enabled;
    private final int bufferSize;
    private final Deque<EventEnvelope> buffer = new ArrayDeque<>();
    private final List<EventConsumer> consumers = new CopyOnWriteArrayList<>();
    private final AtomicLong published = new AtomicLong();
    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    public InMemoryEventBus(
            @Value("${wuye.event.enabled:true}") boolean enabled,
            @Value("${wuye.event.buffer-size:500}") int bufferSize) {
        this.enabled = enabled;
        this.bufferSize = Math.max(1, bufferSize);
    }

    @Override
    public void subscribe(EventConsumer consumer) {
        if (consumer == null) {
            return;
        }
        consumers.add(consumer);
        log.info("event consumer subscribed name={} total={}", consumer.name(), consumers.size());
    }

    @Override
    public void publish(EventEnvelope event) {
        if (event == null || !enabled) {
            return;
        }
        published.incrementAndGet();
        synchronized (buffer) {
            buffer.addLast(event);
            while (buffer.size() > bufferSize) {
                buffer.removeFirst();
            }
        }
        for (EventConsumer consumer : consumers) {
            try {
                consumer.accept(event);
                delivered.incrementAndGet();
            } catch (Exception ex) {
                failed.incrementAndGet();
                // 单个消费者故障不能影响任务主流程，也不能阻断其他消费者。
                log.warn("event consumer failed name={} eventType={} eventId={}",
                        consumer.name(), event.eventType(), event.eventId(), ex);
            }
        }
    }

    @Override
    public List<EventEnvelope> recent(int limit) {
        int size = Math.max(0, Math.min(limit, bufferSize));
        synchronized (buffer) {
            int skip = Math.max(0, buffer.size() - size);
            List<EventEnvelope> list = new ArrayList<>(size);
            int index = 0;
            for (EventEnvelope event : buffer) {
                if (index++ >= skip) {
                    list.add(event);
                }
            }
            return list;
        }
    }

    @Override
    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("enabled", enabled);
        stats.put("bufferSize", bufferSize);
        synchronized (buffer) {
            stats.put("buffered", buffer.size());
        }
        stats.put("published", published.get());
        stats.put("delivered", delivered.get());
        stats.put("failed", failed.get());
        stats.put("consumers", consumers.stream().map(EventConsumer::name).toList());
        return stats;
    }
}
