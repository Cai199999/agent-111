package com.wuye.agent.routing;

/**
 * 路由抽象：规则路由与 Hybrid（规则 + LLM）都实现它。
 */
public interface Router {

    RouteDecision route(String message);

    default RouteDecision route(String message, PendingIntent pending) {
        return route(message);
    }
}
