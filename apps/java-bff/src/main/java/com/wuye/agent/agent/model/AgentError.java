package com.wuye.agent.agent.model;

/**
 * 统一错误对象。category/retryable 用于主 Agent 的重试与 UI 提示。
 */
public record AgentError(String code, String category, boolean retryable, String message) {

    public static AgentError auth(String message) {
        return new AgentError("AUTH_FORBIDDEN", "AUTH", false, message);
    }

    public static AgentError validation(String message) {
        return new AgentError("VALIDATION_ERROR", "VALIDATION", false, message);
    }

    public static AgentError conflict(String message) {
        return new AgentError("TASK_STATE_CONFLICT", "CONFLICT", false, message);
    }

    public static AgentError timeout(String message) {
        return new AgentError("CONFIRM_TIMEOUT", "TIMEOUT", false, message);
    }

    public static AgentError dependency(String message) {
        return new AgentError("DOWNSTREAM_UNAVAILABLE", "DEPENDENCY", true, message);
    }
}
