package com.wuye.agent.routing;

import java.util.Optional;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * LLM 主导、规则兜底：
 * - 正常情况优先使用 Python LLM 的路由/规划结果；
 * - LLM 未启用/超时/不可用：回退规则结果，不阻塞对话。
 */
@Component
@Primary
public class HybridRouter implements Router {

    private final RuleRouter ruleRouter;
    private final LlmRouterClient llmRouterClient;
    private final AiProperties properties;

    public HybridRouter(RuleRouter ruleRouter, LlmRouterClient llmRouterClient, AiProperties properties) {
        this.ruleRouter = ruleRouter;
        this.llmRouterClient = llmRouterClient;
        this.properties = properties;
    }

    @Override
    public RouteDecision route(String message) {
        RouteDecision rule = ruleRouter.route(message);
        // 多操作请求（如两个工单分别派两个人）优先走确定性 batch，
        // 避免 LLM 把它压成一个 assign 后反复追问 ticketNo。
        if ("batch.execute".equals(rule.capabilityId())) {
            return rule;
        }
        if (!properties.isEnabled()) {
            return rule;
        }
        Optional<RouteDecision> llm = llmRouterClient.route(message);
        return choose(rule, llm);
    }

    @Override
    public RouteDecision route(String message, PendingIntent pending) {
        return route(message, pending, null);
    }

    @Override
    public RouteDecision route(String message, PendingIntent pending, String sessionSummary) {
        return route(message, pending, sessionSummary, null);
    }

    @Override
    public RouteDecision route(String message, PendingIntent pending, String sessionSummary,
                               String userProfile) {
        RouteDecision rule = ruleRouter.route(message);
        if ("batch.execute".equals(rule.capabilityId())) {
            return rule;
        }
        if (!properties.isEnabled()) {
            return rule;
        }
        Optional<RouteDecision> llm = llmRouterClient.route(message, pending, sessionSummary, userProfile);
        return choose(rule, llm);
    }

    /**
     * 平台目录追问/批量操作：规则已经给出确定性能力时，不因 LLM 的“泛化澄清”而丢掉上下文。
     * 其他场景仍保持 LLM 主导。
     */
    private RouteDecision choose(RouteDecision rule, Optional<RouteDecision> llm) {
        if (llm.isEmpty()) {
            return rule;
        }
        RouteDecision decision = llm.get();
        String capabilityId = rule.capabilityId();
        boolean deterministic = "platform.catalog".equals(capabilityId)
                || "batch.execute".equals(capabilityId)
                || (capabilityId != null && (capabilityId.startsWith("policy.")
                || capabilityId.startsWith("customer.")
                || capabilityId.startsWith("inspect.")
                || capabilityId.startsWith("gis.")));
        if (deterministic && !rule.needClarification() && decision.needClarification()) {
            return rule;
        }
        // 显式 ticketId 是确定性寻址：LLM 若漏字段或给出同一能力但没有 ticketId，
        // 以规则结果为准，避免真实 0.11.0 写能力因缺 ticketId 被 400。
        Object ruleTicketId = rule.args() == null ? null : rule.args().get("ticketId");
        if (ruleTicketId != null && !rule.needClarification()
                && (decision.needClarification()
                || (rule.capabilityId() != null
                && rule.capabilityId().equals(decision.capabilityId())
                && (decision.args() == null || !decision.args().containsKey("ticketId"))))) {
            return rule;
        }
        // 显式地块编号同理：规则已提取 parcelNo 时，不被 LLM 的“缺字段”覆盖。
        Object ruleParcelNo = rule.args() == null ? null : rule.args().get("parcelNo");
        if (ruleParcelNo != null && !rule.needClarification()
                && (decision.needClarification()
                || (rule.capabilityId() != null
                && rule.capabilityId().equals(decision.capabilityId())
                && (decision.args() == null || !decision.args().containsKey("parcelNo"))))) {
            return rule;
        }
        // GIS 巡查需要先解析地块；规则已有只读前置计划时优先，避免 LLM 漏掉 parcelNo。
        if (rule.hasPlan() && !rule.needClarification()
                && capabilityId != null && capabilityId.startsWith("gis.")
                && (decision.plan().isEmpty()
                || decision.args() == null || !decision.args().containsKey("parcelNo"))) {
            return rule;
        }
        // 合同按名称审查：规则已有 resolve → review 计划时优先，避免 LLM 只追问 contractId。
        if (rule.hasPlan() && !rule.needClarification()
                && capabilityId != null && capabilityId.startsWith("contract.")
                && (decision.needClarification() || decision.plan().isEmpty())) {
            return rule;
        }
        return decision;
    }
}
