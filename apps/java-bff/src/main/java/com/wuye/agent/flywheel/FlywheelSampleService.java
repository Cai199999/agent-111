package com.wuye.agent.flywheel;

import com.wuye.agent.common.Ids;
import com.wuye.agent.event.EventBus;
import com.wuye.agent.event.EventConsumer;
import com.wuye.agent.event.EventEnvelope;
import com.wuye.agent.event.EventTypes;
import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 飞轮样本消费者：订阅任务终态事件，生成样本并落盘。
 *
 * <p>这是“事件出口 + 消费者接口”的第一个真实消费者，用来验证：
 * 任务主流程只发事件、不关心飞轮；飞轮故障不影响任务。</p>
 */
@Service
public class FlywheelSampleService implements EventConsumer {

    private static final Logger log = LoggerFactory.getLogger(FlywheelSampleService.class);
    private static final int DEDUP_CAPACITY = 2000;

    private final EventBus eventBus;
    private final FlywheelSampleStore store;
    private final Ids ids;
    private final Set<String> seenEventIds = new LinkedHashSet<>();
    private final AtomicLong consumed = new AtomicLong();
    private final AtomicLong duplicates = new AtomicLong();
    private final AtomicLong ignored = new AtomicLong();

    public FlywheelSampleService(EventBus eventBus, FlywheelSampleStore store, Ids ids) {
        this.eventBus = eventBus;
        this.store = store;
        this.ids = ids;
    }

    @PostConstruct
    void subscribe() {
        eventBus.subscribe(this);
    }

    @Override
    public String name() {
        return "flywheel-sample-recorder";
    }

    @Override
    public void accept(EventEnvelope event) {
        if (event == null || !isTerminal(event.eventType())) {
            ignored.incrementAndGet();
            return;
        }
        if (!markSeen(event.eventId())) {
            duplicates.incrementAndGet();
            return;
        }
        Map<String, Object> payload = event.payload();
        FlywheelSample sample = new FlywheelSample(
                ids.next("fw_"),
                event.eventId(),
                event.eventType(),
                text(payload, "taskNo"),
                text(payload, "sessionNo"),
                text(payload, "agentKey"),
                text(payload, "capabilityId"),
                event.actorId(),
                event.tenantId(),
                text(payload, "status"),
                map(payload.get("input")),
                payload.get("output"),
                map(payload.get("error")),
                number(payload.get("durationMs")),
                event.occurredAt());
        store.append(sample);
        consumed.incrementAndGet();
        log.info("flywheel sample recorded sampleId={} taskNo={} status={}",
                sample.sampleId(), sample.taskNo(), sample.status());
    }

    public List<FlywheelSample> recent(int limit) {
        return store.recent(limit);
    }

    /** 离线评测导出；单次上限 2000，避免管理端接口拉爆内存。 */
    public List<FlywheelSample> export(int limit) {
        return store.recent(Math.max(1, Math.min(limit, 2000)));
    }

    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>(store.stats());
        stats.put("consumed", consumed.get());
        stats.put("duplicates", duplicates.get());
        stats.put("ignored", ignored.get());
        stats.put("dedupSize", seenEventIds.size());
        return stats;
    }

    private boolean isTerminal(String eventType) {
        return EventTypes.TASK_COMPLETED.equals(eventType)
                || EventTypes.TASK_FAILED.equals(eventType)
                || EventTypes.TASK_CANCELLED.equals(eventType);
    }

    /** 有界幂等集合：至少一次投递下的重复消费保护。 */
    private synchronized boolean markSeen(String eventId) {
        if (eventId == null) {
            return true;
        }
        if (!seenEventIds.add(eventId)) {
            return false;
        }
        if (seenEventIds.size() > DEDUP_CAPACITY) {
            var iterator = seenEventIds.iterator();
            if (iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
        }
        return true;
    }

    private static String text(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }

    private static Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> copy = new LinkedHashMap<>();
            source.forEach((key, item) -> copy.put(String.valueOf(key), item));
            return copy;
        }
        return Map.of();
    }
}
