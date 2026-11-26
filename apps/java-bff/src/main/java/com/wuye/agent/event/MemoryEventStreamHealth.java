package com.wuye.agent.event;

import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * memory 后端没有跨进程 Stream，健康视图只标记 backend。
 */
@Component
@ConditionalOnProperty(name = "wuye.event.backend", havingValue = "memory", matchIfMissing = true)
public class MemoryEventStreamHealth implements EventStreamHealth {

    @Override
    public Map<String, Object> stats() {
        return Map.of("backend", "MEMORY");
    }
}
