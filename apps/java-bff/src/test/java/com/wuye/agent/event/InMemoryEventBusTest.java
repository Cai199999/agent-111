package com.wuye.agent.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * 事件总线：有界缓冲、消费者隔离、关闭开关。
 */
class InMemoryEventBusTest {

    @Test
    void buffersRecentEventsWithBoundedCapacity() {
        InMemoryEventBus bus = new InMemoryEventBus(true, 3);
        for (int i = 0; i < 5; i++) {
            bus.publish(event("evt-" + i));
        }
        List<EventEnvelope> recent = bus.recent(10);
        assertEquals(3, recent.size());
        assertEquals("evt-2", recent.get(0).eventId());
        assertEquals("evt-4", recent.get(2).eventId());
        assertEquals(5L, bus.stats().get("published"));
    }

    @Test
    void consumerFailureDoesNotBlockOtherConsumers() {
        InMemoryEventBus bus = new InMemoryEventBus(true, 10);
        AtomicInteger received = new AtomicInteger();
        bus.subscribe(new EventConsumer() {
            @Override
            public String name() {
                return "broken";
            }

            @Override
            public void accept(EventEnvelope event) {
                throw new IllegalStateException("boom");
            }
        });
        bus.subscribe(new EventConsumer() {
            @Override
            public String name() {
                return "healthy";
            }

            @Override
            public void accept(EventEnvelope event) {
                received.incrementAndGet();
            }
        });

        bus.publish(event("evt-1"));

        assertEquals(1, received.get());
        assertEquals(1L, bus.stats().get("failed"));
        assertEquals(1L, bus.stats().get("delivered"));
    }

    @Test
    void disabledBusStoresNothing() {
        InMemoryEventBus bus = new InMemoryEventBus(false, 10);
        bus.publish(event("evt-1"));
        assertTrue(bus.recent(10).isEmpty());
        assertEquals(0L, bus.stats().get("published"));
    }

    private EventEnvelope event(String id) {
        return new EventEnvelope(id, EventTypes.TASK_REQUESTED, 1, Instant.now(),
                "test", "trc", "10001", "0", "wt_1",
                EventTypes.TASK_REQUESTED + ":wt_1", Map.of("taskNo", "wt_1"));
    }
}
