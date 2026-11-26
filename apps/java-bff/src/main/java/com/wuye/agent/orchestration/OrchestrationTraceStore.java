package com.wuye.agent.orchestration;

import java.util.List;
import java.util.Map;

/**
 * 编排审计存储。memory 用于默认/测试，MySQL 用于 mysql profile 的跨重启回放。
 */
public interface OrchestrationTraceStore {

    void append(OrchestrationTrace trace);

    /** 最近记录，时间升序。 */
    List<OrchestrationTrace> recent(int limit);

    Map<String, Object> stats();
}
