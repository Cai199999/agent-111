package com.wuye.agent.event;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis Stream 健康视图：长度、pending、consumer 数、DLQ 长度与本地计数。
 *
 * <p>`lag` 依赖 Redis 7 的 entries-read，Spring Data 3.5 未暴露；这里用
 * stream 长度 + pending + 消费计数组合判断，避免伪造指标。</p>
 */
@Component
@ConditionalOnProperty(name = "wuye.event.backend", havingValue = "redis")
public class RedisStreamHealth implements EventStreamHealth {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamHealth.class);

    private final StringRedisTemplate redis;
    private final String stream;
    private final String group;
    private final String dlq;
    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong deadLettered = new AtomicLong();
    private volatile Instant lastReadAt;
    private volatile String lastError;

    public RedisStreamHealth(
            StringRedisTemplate redis,
            @Value("${wuye.event.redis.stream:wuye_agent:events}") String stream,
            @Value("${wuye.event.redis.group:wuye_agent-bff}") String group,
            @Value("${wuye.event.redis.dlq:wuye_agent:events:dlq}") String dlq) {
        this.redis = redis;
        this.stream = stream;
        this.group = group;
        this.dlq = dlq;
    }

    public void recordDelivered(long count) {
        delivered.addAndGet(Math.max(0, count));
        lastReadAt = Instant.now();
    }

    public void recordFailure(String error) {
        failed.incrementAndGet();
        lastError = truncate(error);
        lastReadAt = Instant.now();
    }

    public void recordDeadLetter() {
        deadLettered.incrementAndGet();
    }

    @Override
    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("backend", "REDIS");
        stats.put("stream", stream);
        stats.put("group", group);
        stats.put("dlq", dlq);
        try {
            StreamOperations<String, String, String> ops = redis.opsForStream();
            stats.put("length", streamLength(ops, stream));
            stats.put("dlqLength", streamLength(ops, dlq));
            GroupInfo info = groupInfo(ops);
            stats.put("consumers", info.consumers);
            stats.put("pending", info.pending);
            stats.put("lastDeliveredId", info.lastDeliveredId);
        } catch (Exception ex) {
            log.warn("redis stream health query failed: {}", ex.getMessage());
            stats.put("error", ex.getMessage());
        }
        stats.put("delivered", delivered.get());
        stats.put("failed", failed.get());
        stats.put("deadLettered", deadLettered.get());
        stats.put("lastReadAt", lastReadAt);
        stats.put("lastError", lastError);
        return stats;
    }

    private long streamLength(StreamOperations<String, String, String> ops, String key) {
        try {
            return ops.info(key).streamLength();
        } catch (Exception ex) {
            // key 不存在时视为 0，不上报错误。
            return 0;
        }
    }

    private GroupInfo groupInfo(StreamOperations<String, String, String> ops) {
        try {
            for (StreamInfo.XInfoGroup item : ops.groups(stream)) {
                if (group.equals(item.groupName())) {
                    return new GroupInfo(
                            item.consumerCount() == null ? 0 : item.consumerCount(),
                            item.pendingCount() == null ? 0 : item.pendingCount(),
                            item.lastDeliveredId());
                }
            }
        } catch (Exception ex) {
            log.debug("redis stream group info unavailable: {}", ex.getMessage());
        }
        return new GroupInfo(0, 0, null);
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private record GroupInfo(long consumers, long pending, String lastDeliveredId) {
    }
}
