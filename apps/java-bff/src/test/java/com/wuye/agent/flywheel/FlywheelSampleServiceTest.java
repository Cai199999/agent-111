package com.wuye.agent.flywheel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuye.agent.common.Ids;
import com.wuye.agent.event.EventEnvelope;
import com.wuye.agent.event.EventTypes;
import com.wuye.agent.event.InMemoryEventBus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 飞轮消费者：只消费终态事件、幂等去重、JSONL 落盘。
 */
class FlywheelSampleServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void recordsTerminalTaskEventAndIgnoresDuplicates() throws Exception {
        InMemoryEventBus bus = new InMemoryEventBus(true, 100);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        JsonlFlywheelSampleStore store = new JsonlFlywheelSampleStore(
                mapper, tempDir.toString(), 10);
        FlywheelSampleService service = new FlywheelSampleService(bus, store, new Ids());
        service.subscribe();

        bus.publish(terminal("evt-1", EventTypes.TASK_COMPLETED, "SUCCEEDED"));
        bus.publish(terminal("evt-1", EventTypes.TASK_COMPLETED, "SUCCEEDED"));
        bus.publish(terminal("evt-2", EventTypes.TASK_REQUESTED, "RUNNING"));

        List<FlywheelSample> samples = service.recent(10);
        assertEquals(1, samples.size());
        assertEquals("wt_1", samples.get(0).taskNo());
        assertEquals("SUCCEEDED", samples.get(0).status());

        Path file = tempDir.resolve("samples.jsonl");
        assertTrue(Files.exists(file));
        String content = Files.readString(file);
        assertTrue(content.contains("\"taskNo\":\"wt_1\""), content);
        assertEquals(1L, service.stats().get("consumed"));
        assertEquals(1L, service.stats().get("duplicates"));
        assertEquals(1L, service.stats().get("ignored"));
    }

    private EventEnvelope terminal(String eventId, String type, String status) {
        Map<String, Object> payload = Map.of(
                "taskNo", "wt_1",
                "sessionNo", "ws_1",
                "agentKey", "property-maintenance",
                "capabilityId", "property.ticket.create",
                "status", status,
                "input", Map.of("description", "灯坏了"),
                "output", Map.of("ticketNo", "GD1"),
                "durationMs", 120L);
        return new EventEnvelope(eventId, type, 1, Instant.now(), "test",
                "trc", "10001", "0", "wt_1", type + ":wt_1", payload);
    }
}
