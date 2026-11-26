package com.wuye.agent.event;

import java.util.List;
import java.util.Map;

/**
 * 进程内事件总线能力：发布 + 订阅 + 最近事件查询。
 *
 * <p>当前实现只保证单机；多实例顺序/去重由未来 Kafka 分区键
 * （{@link EventEnvelope#subject()}）与消费端幂等表兜底。</p>
 */
public interface EventBus extends EventPublisher {

    void subscribe(EventConsumer consumer);

    /** 最近事件，按发生时间升序。 */
    List<EventEnvelope> recent(int limit);

    /** 运行指标，供管理端排障。 */
    Map<String, Object> stats();
}
