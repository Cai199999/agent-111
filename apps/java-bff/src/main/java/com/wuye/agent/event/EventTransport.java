package com.wuye.agent.event;

/**
 * Outbox relay 的下游传输：memory 直接进本地总线，redis 写入 Redis Stream。
 *
 * <p>职责分离：Outbox 负责可靠落库，Transport 负责“发出去”，
 * 本地 EventBus 只负责进程内消费者扇出。</p>
 */
public interface EventTransport {

    void send(EventEnvelope event);
}
