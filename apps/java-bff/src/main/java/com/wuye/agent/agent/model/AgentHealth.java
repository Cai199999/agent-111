package com.wuye.agent.agent.model;

/**
 * 健康/就绪快照。health=存活，ready=依赖就绪。
 */
public record AgentHealth(String agentKey, String health, String ready, long latencyMs) {
}
