package com.wuye.agent.orchestration;

import java.time.Instant;
import java.util.List;

/**
 * 一次编排阶段的审计记录：只含阶段/判定/能力/步骤状态与澄清文本。
 */
public record OrchestrationTrace(
        String traceId,
        String sessionNo,
        String phase,
        String verdict,
        String clarification,
        List<String> capabilities,
        List<String> statuses,
        Instant occurredAt
) {
    public OrchestrationTrace {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        statuses = statuses == null ? List.of() : List.copyOf(statuses);
    }
}
