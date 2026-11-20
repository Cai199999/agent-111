package com.wuye.agent.routing;

import java.util.Optional;

/**
 * LLM 路由客户端抽象，便于测试替换。
 */
public interface LlmRouterClient {

    Optional<RouteDecision> route(String message);
}
