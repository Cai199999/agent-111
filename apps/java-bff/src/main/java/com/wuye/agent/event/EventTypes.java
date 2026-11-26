package com.wuye.agent.event;

/**
 * 事件类型常量（v1 冻结清单）。
 *
 * <p>命名规则：{@code <域>.<聚合>.<动作>}，全小写、点分。
 * 新增事件必须同时更新 {@code docs/08-主Agent演进-事件契约与编排骨架.md}。</p>
 */
public final class EventTypes {

    /** 任务已受理，即将调用（或已调用）子 Agent。 */
    public static final String TASK_REQUESTED = "agent.task.requested";

    /** 任务进入人工确认，等待用户批准/拒绝。载荷绝不包含确认令牌。 */
    public static final String TASK_CONFIRMATION_REQUESTED = "agent.task.confirmation.requested";

    /** 任务成功终态。 */
    public static final String TASK_COMPLETED = "agent.task.completed";

    /** 任务失败或拒绝终态。 */
    public static final String TASK_FAILED = "agent.task.failed";

    /** 任务被取消终态。 */
    public static final String TASK_CANCELLED = "agent.task.cancelled";

    /** 飞轮样本已落盘（由底座内部消费任务终态事件后产生）。 */
    public static final String FLYWHEEL_SAMPLE_RECORDED = "agent.flywheel.sample.recorded";

    private EventTypes() {
    }
}
