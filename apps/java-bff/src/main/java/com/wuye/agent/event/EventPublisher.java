package com.wuye.agent.event;

/**
 * 事件出口。业务代码只依赖本接口，不感知内存队列、Redis Stream 或 Kafka。
 *
 * <p>投递语义：至少一次（at-least-once）。实现必须保证 publish 不抛出业务异常，
 * 事件通道故障不能阻断任务主流程。</p>
 */
public interface EventPublisher {

    void publish(EventEnvelope event);

    /** 关闭事件通道时使用；调用方无需判空。 */
    static EventPublisher noop() {
        return event -> {
        };
    }
}
