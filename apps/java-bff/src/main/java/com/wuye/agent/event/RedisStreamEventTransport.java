package com.wuye.agent.event;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis Stream 传输：XADD 到本项目 Stream，并做近似 MAXLEN 限制。
 *
 * <p>失败会抛给 OutboxEventPublisher，由其标记 PENDING 并由 relay 重试；
 * 不会在发送失败时丢事件。</p>
 */
@Component
@ConditionalOnProperty(name = "wuye.event.backend", havingValue = "redis")
public class RedisStreamEventTransport implements EventTransport {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamEventTransport.class);

    private final StringRedisTemplate redis;
    private final RedisEventCodec codec;
    private final String stream;
    private final long maxLen;

    public RedisStreamEventTransport(
            StringRedisTemplate redis,
            RedisEventCodec codec,
            @Value("${wuye.event.redis.stream:wuye_agent:events}") String stream,
            @Value("${wuye.event.redis.maxlen:10000}") long maxLen) {
        this.redis = redis;
        this.codec = codec;
        this.stream = stream;
        this.maxLen = Math.max(100, maxLen);
    }

    @Override
    public void send(EventEnvelope event) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("event", codec.encode(event));
        fields.put("eventId", event.eventId());
        fields.put("eventType", event.eventType());
        redis.opsForStream().add(
                StreamRecords.mapBacked(fields).withStreamKey(stream));
        try {
            // 只限制本项目 Stream 长度，不影响其他 key。
            redis.opsForStream().trim(stream, maxLen, true);
        } catch (Exception ex) {
            log.warn("redis stream trim failed stream={} error={}", stream, ex.getMessage());
        }
    }
}
