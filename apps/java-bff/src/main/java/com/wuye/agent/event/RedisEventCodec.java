package com.wuye.agent.event;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Redis Stream 里的事件序列化：整封 EventEnvelope 转 JSON。
 *
 * <p>独立成类便于单测；Redis 里只保存事件本身，不保存 outbox 状态。</p>
 */
@Component
@ConditionalOnProperty(name = "wuye.event.backend", havingValue = "redis")
public class RedisEventCodec {

    private final ObjectMapper mapper;

    public RedisEventCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String encode(EventEnvelope event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (Exception ex) {
            throw new IllegalStateException("event encode failed: " + ex.getMessage(), ex);
        }
    }

    public EventEnvelope decode(String json) {
        try {
            return mapper.readValue(json, EventEnvelope.class);
        } catch (Exception ex) {
            throw new IllegalStateException("event decode failed: " + ex.getMessage(), ex);
        }
    }
}
