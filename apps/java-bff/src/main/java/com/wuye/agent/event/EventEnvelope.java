package com.wuye.agent.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 主 Agent 事件信封 v1。
 *
 * <p>字段口径一旦冻结，v1 内只允许新增可选字段，不允许改名/改语义；
 * 破坏性变更必须升级 {@code eventVersion} 并新增事件类型。</p>
 *
 * <p>约定：</p>
 * <ul>
 *   <li>{@code eventId}：全局唯一，消费端幂等去重的第一选择；</li>
 *   <li>{@code idempotencyKey}：业务幂等键，同一任务同一事件类型只应出现一次；</li>
 *   <li>{@code subject}：聚合根（任务号/单据号），分区与顺序按它保证；</li>
 *   <li>{@code payload}：事件私有载荷，禁止放令牌、密钥、密码、完整附件内容。</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventEnvelope(
        String eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        String source,
        String traceId,
        String actorId,
        String tenantId,
        String subject,
        String idempotencyKey,
        Map<String, Object> payload
) {

    /** 当前信封版本。 */
    public static final int VERSION = 1;

    public EventEnvelope {
        // 不复制为不可变 Map 会允许调用方后续修改；这里保留插入顺序，且允许 null 值。
        payload = payload == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }
}
