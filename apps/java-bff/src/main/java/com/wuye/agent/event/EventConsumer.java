package com.wuye.agent.event;

/**
 * 事件消费者。实现必须幂等：以 {@link EventEnvelope#eventId()} 或
 * {@link EventEnvelope#idempotencyKey()} 去重。
 */
public interface EventConsumer {

    /** 消费者名称，用于日志与排障。 */
    String name();

    /** 处理单个事件；抛异常由事件总线隔离并记录，不影响其他消费者。 */
    void accept(EventEnvelope event);
}
