package com.wuye.agent.orchestration;

import com.wuye.agent.agent.model.Capability;
import com.wuye.agent.routing.RouteDecision;
import java.util.List;
import java.util.Optional;

/**
 * 编排网关：Java 与 Python 编排图之间的唯一通道。
 *
 * <p>拆成接口是为了单测可注入假实现，HTTP 实现失败时必须返回 empty，
 * 由调用方回退到现有同步流程，不能阻塞用户消息。</p>
 */
public interface OrchestrationGateway {

    Optional<OrchestrationResult> plan(PlanRequest request);

    Optional<OrchestrationResult> verify(VerifyRequest request);

    record PlanRequest(
            String userMessage,
            String traceId,
            String sessionNo,
            String actorId,
            String tenantId,
            List<Capability> capabilities,
            List<RouteDecision.PlanStep> plan
    ) {
    }

    record VerifyRequest(
            String traceId,
            String sessionNo,
            List<RouteDecision.PlanStep> plan,
            List<OrchestrationResult.StepResult> steps
    ) {
    }
}
