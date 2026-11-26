package com.wuye.agent.event;

import java.util.Map;

/**
 * 事件通道健康视图。memory 与 redis 各有一个实现，管理端只依赖本接口。
 */
public interface EventStreamHealth {

    Map<String, Object> stats();
}
