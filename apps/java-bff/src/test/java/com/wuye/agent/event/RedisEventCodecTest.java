package com.wuye.agent.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Redis Stream 事件编解码：信封全字段、Instant、嵌套载荷。
 */
class RedisEventCodecTest {

    private final RedisEventCodec codec = new RedisEventCodec(
            new ObjectMapper().findAndRegisterModules());

    @Test
    void roundTripsEnvelope() {
        EventEnvelope event = new EventEnvelope(
                "evt-1", EventTypes.TASK_COMPLETED, 1,
                Instant.parse("2026-11-26T01:00:00Z"),
                "wuye-agent-bff", "trc-1", "10001", "0",
                "wt-1", "agent.task.completed:wt-1",
                Map.of("taskNo", "wt-1",
                        "status", "SUCCEEDED",
                        "output", Map.of("ticketNo", "GD1"),
                        "items", List.of("a", "b")));

        String json = codec.encode(event);
        EventEnvelope decoded = codec.decode(json);

        assertEquals("evt-1", decoded.eventId());
        assertEquals(EventTypes.TASK_COMPLETED, decoded.eventType());
        assertEquals(1, decoded.eventVersion());
        assertEquals(Instant.parse("2026-11-26T01:00:00Z"), decoded.occurredAt());
        assertEquals("wt-1", decoded.subject());
        assertEquals("agent.task.completed:wt-1", decoded.idempotencyKey());
        assertEquals("SUCCEEDED", decoded.payload().get("status"));
        assertTrue(decoded.payload().get("output") instanceof Map<?, ?>);
    }

    @Test
    void invalidJsonFailsLoudly() {
        assertThrows(IllegalStateException.class, () -> codec.decode("{oops"));
    }
}
