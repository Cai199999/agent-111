package com.wuye.agent.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Outbox 发布器：先落库、同步投递、失败重试、重启补发、关闭直投。
 */
class OutboxEventPublisherTest {

    @Test
    void publishAppendsThenDispatchesAndMarksDone() {
        InMemoryEventOutboxStore store = new InMemoryEventOutboxStore(3);
        FakeBus bus = new FakeBus();
        OutboxEventPublisher publisher = new OutboxEventPublisher(store, new LocalEventTransport(bus), true);

        publisher.publish(event("evt-1", EventTypes.TASK_REQUESTED));

        assertEquals(1, bus.received.size());
        assertEquals(1L, store.stats().get("dispatched"));
        assertEquals(0, store.pending(10).size());
    }

    @Test
    void dispatchFailureRetriesThenMarksFailed() {
        InMemoryEventOutboxStore store = new InMemoryEventOutboxStore(3);
        FakeBus bus = new FakeBus();
        bus.fail = true;
        OutboxEventPublisher publisher = new OutboxEventPublisher(store, new LocalEventTransport(bus), true);
        EventEnvelope event = event("evt-2", EventTypes.TASK_COMPLETED);

        publisher.publish(event);
        assertEquals(1, store.pending(10).size());

        publisher.relay();
        publisher.relay();

        assertEquals(0, store.pending(10).size());
        assertEquals(1L, store.stats().get("failed"));
    }

    @Test
    void relayRepublishesPendingEventAfterRestart() {
        InMemoryEventOutboxStore store = new InMemoryEventOutboxStore(3);
        EventEnvelope event = event("evt-3", EventTypes.TASK_CONFIRMATION_REQUESTED);
        // 模拟“写库成功但投递前进程中断”。
        store.append(event);

        FakeBus bus = new FakeBus();
        OutboxEventPublisher publisher = new OutboxEventPublisher(store, new LocalEventTransport(bus), true);
        publisher.relay();

        assertEquals(1, bus.received.size());
        assertEquals("evt-3", bus.received.get(0).eventId());
        assertEquals(0, store.pending(10).size());
    }

    @Test
    void disabledOutboxPublishesDirectlyWithoutStoring() {
        InMemoryEventOutboxStore store = new InMemoryEventOutboxStore(3);
        FakeBus bus = new FakeBus();
        OutboxEventPublisher publisher = new OutboxEventPublisher(store, new LocalEventTransport(bus), false);

        publisher.publish(event("evt-4", EventTypes.TASK_REQUESTED));

        assertEquals(1, bus.received.size());
        assertEquals(0L, store.stats().get("appended"));
        assertEquals(0, store.pending(10).size());
    }

    private EventEnvelope event(String id, String type) {
        return new EventEnvelope(id, type, 1, Instant.now(), "test",
                "trc", "10001", "0", "wt_1", type + ":wt_1",
                Map.of("taskNo", "wt_1"));
    }

    private static final class FakeBus implements EventBus {
        private final List<EventEnvelope> received = new ArrayList<>();
        private boolean fail;

        @Override
        public void subscribe(EventConsumer consumer) {
        }

        @Override
        public List<EventEnvelope> recent(int limit) {
            return received;
        }

        @Override
        public Map<String, Object> stats() {
            return Map.of("received", received.size());
        }

        @Override
        public void publish(EventEnvelope event) {
            if (fail) {
                throw new IllegalStateException("bus down");
            }
            received.add(event);
        }
    }
}
