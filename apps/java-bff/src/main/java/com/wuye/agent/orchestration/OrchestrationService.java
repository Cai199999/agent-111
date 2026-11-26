package com.wuye.agent.orchestration;

import com.wuye.agent.agent.adapter.AdapterRegistry;
import com.wuye.agent.agent.model.Capability;
import com.wuye.agent.auth.AuthUser;
import com.wuye.agent.auth.PolicyService;
import com.wuye.agent.memory.MainAgentTools;
import com.wuye.agent.routing.AiProperties;
import com.wuye.agent.routing.RouteDecision;
import com.wuye.agent.store.TaskRecord;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 编排接入层：
 * 1) 执行前把现有 LLM 路由的 plan 交给 Python 校验（PLAN_ONLY）；
 * 2) 执行后把结果回传 Python 做结构化验收。
 *
 * <p>任何失败都回退到现有同步流程，不改变原有可用性。</p>
 */
@Service
public class OrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationService.class);

    private final OrchestrationGateway gateway;
    private final AdapterRegistry registry;
    private final PolicyService policyService;
    private final AiProperties properties;

    public OrchestrationService(OrchestrationGateway gateway, AdapterRegistry registry,
                                PolicyService policyService, AiProperties properties) {
        this.gateway = gateway;
        this.registry = registry;
        this.policyService = policyService;
        this.properties = properties;
    }

    /**
     * 执行前校验计划。返回 empty 表示编排未启用/不适用/不可用，调用方保持原逻辑。
     */
    public Optional<OrchestrationOutcome> reviewPlan(RouteDecision route, String userMessage,
                                                     AuthUser user, String sessionNo,
                                                     String traceId) {
        if (!enabled() || route == null || user == null) {
            return Optional.empty();
        }
        if (route.needClarification() || route.capabilityId() == null) {
            return Optional.empty();
        }
        // 主 Agent 自省工具、批量工具不是子 Agent 能力，不进入编排。
        if (MainAgentTools.isMainTool(route.capabilityId())) {
            return Optional.empty();
        }
        // 附件链路已有专用结构化验收，避免重复回问。
        if ("ATTACHMENT".equals(route.router())) {
            return Optional.empty();
        }

        List<Capability> allowed = policyService.allowedCapabilities(user, registry.capabilities());
        boolean routeAllowed = allowed.stream()
                .anyMatch(capability -> route.capabilityId().equals(capability.capabilityId()));
        if (!routeAllowed) {
            // 权限问题交给现有鉴权流程处理，编排不改变 403 语义。
            return Optional.empty();
        }

        List<RouteDecision.PlanStep> plan = route.hasPlan()
                ? route.plan()
                : List.of(new RouteDecision.PlanStep(route.capabilityId(), route.args()));
        try {
            return gateway.plan(new OrchestrationGateway.PlanRequest(
                    userMessage, traceId, sessionNo, user.actorId(), user.tenantId(),
                    allowed, plan)).map(this::outcome);
        } catch (Exception ex) {
            log.warn("编排计划失败，回退同步流程 traceId={} error={}", traceId, ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 执行后结构化验收。只对终态任务生效；非终态任务无需验收。
     */
    public Optional<OrchestrationOutcome> verify(Capability capability, TaskRecord task,
                                                 List<RouteDecision.PlanStep> plan,
                                                 String sessionNo, String traceId) {
        if (!enabled() || capability == null || task == null || !task.status.terminal()) {
            return Optional.empty();
        }
        OrchestrationResult.StepResult step = new OrchestrationResult.StepResult(
                capability.capabilityId(),
                capability.agentKey(),
                task.status.name(),
                task.output,
                task.error == null ? null : task.error.message(),
                List.of());
        try {
            return gateway.verify(new OrchestrationGateway.VerifyRequest(
                    traceId, sessionNo, plan, List.of(step))).map(result -> new OrchestrationOutcome(
                    result.verdict(),
                    result.clarification(),
                    plan,
                    // Python 验收响应只回状态；SSE 需要展示的步骤取自 Java 侧事实。
                    result.steps().isEmpty() ? List.of(step) : result.steps(),
                    result.notes()));
        } catch (Exception ex) {
            log.warn("编排验收失败，保留原任务结果 traceId={} error={}", traceId, ex.getMessage());
            return Optional.empty();
        }
    }

    private boolean enabled() {
        return properties.isEnabled() && properties.isOrchestrationEnabled();
    }

    private OrchestrationOutcome outcome(OrchestrationResult result) {
        return new OrchestrationOutcome(
                result.verdict(),
                result.clarification(),
                result.plan(),
                result.steps(),
                result.notes());
    }
}
