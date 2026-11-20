package com.wuye.agent.agent.http;

/**
 * 两种已接入方言：包络、错误码、确认体不同，由 HTTP Adapter 各自处理。
 */
public enum AgentDialect {
    PROPERTY,
    CONTRACT
}
