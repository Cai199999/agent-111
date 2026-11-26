package com.wuye.agent.flywheel;

import java.time.Instant;
import java.util.Map;

/**
 * 飞轮样本：一次任务从输入到结果的完整快照。
 *
 * <p>第一阶段只落文件（JSONL），用于后续离线评测、偏好数据与提示词迭代；
 * 不接训练栈、不做自动训练。样本中包含业务明文，只允许内网存储。</p>
 *
 * @param sampleId     样本 ID
 * @param eventId      来源事件 ID，用于幂等
 * @param eventType    来源事件类型
 * @param taskNo       任务号
 * @param sessionNo    会话号
 * @param agentKey     子 Agent
 * @param capabilityId 能力
 * @param actorId      发起人
 * @param tenantId     租户
 * @param status       终态（SUCCEEDED/FAILED/REJECTED/CANCELLED）
 * @param input        任务输入
 * @param output       子 Agent 原始输出（失败时为空）
 * @param error        统一错误（成功时为空）
 * @param durationMs   任务耗时
 * @param occurredAt   终态发生时间
 */
public record FlywheelSample(
        String sampleId,
        String eventId,
        String eventType,
        String taskNo,
        String sessionNo,
        String agentKey,
        String capabilityId,
        String actorId,
        String tenantId,
        String status,
        Map<String, Object> input,
        Object output,
        Map<String, Object> error,
        long durationMs,
        Instant occurredAt
) {
}
