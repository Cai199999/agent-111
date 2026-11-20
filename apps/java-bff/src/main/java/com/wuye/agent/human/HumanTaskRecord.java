package com.wuye.agent.human;

import java.time.Instant;

public record HumanTaskRecord(
        String taskNo,
        String status,
        Long assigneeId,
        String assigneeName,
        String note,
        Instant createdAt,
        Instant updatedAt
) {
}
