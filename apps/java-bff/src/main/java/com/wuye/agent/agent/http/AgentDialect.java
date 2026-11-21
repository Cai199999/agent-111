package com.wuye.agent.agent.http;

/**
 * 两种已接入方言：包络、错误码、确认体不同，由 HTTP Adapter 各自处理。
 */
public enum AgentDialect {
    PROPERTY,
    CONTRACT,
    /** 标准裸对象契约；运行时安装的外部 Agent 默认走这个方言。 */
    CANONICAL
}
