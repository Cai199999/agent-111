package com.wuye.agent.event;

import java.util.List;
import java.util.Map;

/**
 * 事件 Outbox 存储：先落库再投递，保证进程崩溃/重启后事件不丢。
 *
 * <p>当前 relay 把 PENDING 事件投递到进程内总线；未来替换 Kafka/Redis Stream 时，
 * 本接口与表结构保持不变，只换 relay 的下游。</p>
 */
public interface EventOutboxStore {

    void append(EventEnvelope event);

    /** 待投递事件，按创建时间升序。 */
    List<EventEnvelope> pending(int limit);

    /** 最近事件（含已投递），按时间升序；用于重启后的管理端观测。 */
    List<EventEnvelope> recent(int limit);

    void markDispatched(String eventId);

    /** 投递失败：累加 attempts，超过上限后标记 FAILED，不再无限重试。 */
    void markFailed(String eventId, String error);

    Map<String, Object> stats();
}
