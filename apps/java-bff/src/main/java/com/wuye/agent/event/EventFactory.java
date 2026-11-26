package com.wuye.agent.event;

import com.wuye.agent.common.Ids;
import java.time.Instant;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 事件信封工厂：统一生成 eventId / 版本 / 来源 / 幂等键。
 *
 * <p>业务代码只提供事件类型、聚合根与载荷，避免各调用点信封字段不一致。</p>
 */
@Component
public class EventFactory {

    private final Ids ids;
    private final String source;

    public EventFactory(Ids ids, @Value("${wuye.event.source:wuye-agent-bff}") String source) {
        this.ids = ids;
        this.source = source == null || source.isBlank() ? "wuye-agent-bff" : source;
    }

    /**
     * @param idempotencyKey 业务幂等键；调用方通常传 {@code eventType:subject}
     */
    public EventEnvelope create(String eventType, String subject, String idempotencyKey,
                                String traceId, String actorId, String tenantId,
                                Map<String, Object> payload) {
        return new EventEnvelope(
                ids.next("evt_"),
                eventType,
                EventEnvelope.VERSION,
                Instant.now(),
                source,
                traceId,
                actorId,
                tenantId,
                subject,
                idempotencyKey,
                payload);
    }
}
