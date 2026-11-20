package com.wuye.agent.agent.model;

/**
 * 主 Agent 统一任务状态。
 *
 * <p>REJECTED 是物业原生终态；合同侧的 FAILED(CONFIRM_REJECTED) 由 Adapter 归一化到这里。</p>
 */
public enum TaskStatus {
    PENDING,
    RUNNING,
    WAITING_CONFIRM,
    SUCCEEDED,
    FAILED,
    REJECTED,
    CANCELLED;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == REJECTED || this == CANCELLED;
    }

    public boolean active() {
        return this == PENDING || this == RUNNING || this == WAITING_CONFIRM;
    }
}
