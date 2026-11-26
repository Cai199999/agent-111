package com.wuye.agent.task;

import com.wuye.agent.agent.adapter.AdapterRegistry;
import com.wuye.agent.agent.model.AgentError;
import com.wuye.agent.agent.model.Capability;
import com.wuye.agent.agent.model.TaskStatus;
import com.wuye.agent.human.HumanInterventionService;
import com.wuye.agent.routing.LlmRouterClient;
import com.wuye.agent.routing.RecoveryDecision;
import com.wuye.agent.store.TaskRecord;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * A2A 双向链路的自修复：
 * <ol>
 *   <li>读取子 Agent 的失败反馈（error.category/code/message）；</li>
 *   <li>VALIDATION / NOT_FOUND 交给 LLM 决策：追问、修正参数、换能力或兜底；</li>
 *   <li>DEPENDENCY / TIMEOUT / RATE_LIMIT 对只读能力自动重试；</li>
 *   <li>只有确实没有能力时才告诉用户“目前没有这个功能”。</li>
 * </ol>
 *
 * <p>写操作不做自动重试，避免重复执行；失败统一进人工介入队列。</p>
 */
@Service
public class TaskRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(TaskRecoveryService.class);

    private final TaskService taskService;
    private final AdapterRegistry registry;
    private final LlmRouterClient llm;
    private final AgentErrorTranslator translator;
    private final HumanInterventionService humanInterventionService;

    public TaskRecoveryService(TaskService taskService, AdapterRegistry registry,
                               LlmRouterClient llm, AgentErrorTranslator translator,
                               HumanInterventionService humanInterventionService) {
        this.taskService = taskService;
        this.registry = registry;
        this.llm = llm;
        this.translator = translator;
        this.humanInterventionService = humanInterventionService;
    }

    public RecoveryOutcome recover(String sessionNo, String userMessage, Capability capability,
                                   Map<String, Object> args, TaskRecord task,
                                   String actorId, String traceId) {
        return recover(sessionNo, userMessage, capability, args, task, actorId, traceId, 0);
    }

    private RecoveryOutcome recover(String sessionNo, String userMessage, Capability capability,
                                    Map<String, Object> args, TaskRecord task,
                                    String actorId, String traceId, int depth) {
        if (task == null) {
            return RecoveryOutcome.fallback(null, capability, "TRANSFERRED",
                    "这次没有拿到子 Agent 的反馈，我已经转人工跟进。");
        }
        if (task.status == TaskStatus.SUCCEEDED || task.status == TaskStatus.WAITING_CONFIRM
                || task.status == TaskStatus.PENDING || task.status == TaskStatus.RUNNING) {
            return RecoveryOutcome.unchanged(task, capability);
        }
        if (task.status == TaskStatus.REJECTED) {
            return RecoveryOutcome.fallback(task, capability, "REJECTED",
                    "你已拒绝这次操作。如果还需要继续，可以重新发起。");
        }

        AgentError error = task.error == null
                ? new AgentError("UNKNOWN", "UNKNOWN", false, "任务没有完成")
                : task.error;
        String category = error.category() == null ? "UNKNOWN" : error.category().toUpperCase();

        // 参数问题/能力不存在/未知错误：先让 LLM 决定追问、修正还是换能力。
        if (depth == 0 && isDecisionCategory(category)) {
            Optional<RecoveryDecision> decision = llm.recover(
                    userMessage,
                    capability == null ? null : capability.capabilityId(),
                    args == null ? Map.of() : args,
                    error.code(), error.message(), category);
            if (decision.isPresent()) {
                RecoveryOutcome outcome = applyDecision(sessionNo, userMessage, capability, args,
                        task, actorId, traceId, depth, error, decision.get());
                if (outcome != null) {
                    return outcome;
                }
            }
        }

        // 依赖/超时/限流：只读能力自动重试两次；写能力不自动重试，避免重复写。
        if (depth == 0 && isTransient(category, error)
                && capability != null && capability.readOnly()) {
            TaskRecord current = task;
            for (int attempt = 1; attempt <= 2; attempt++) {
                sleep(200L * attempt);
                humanInterventionService.autoResolve(current.taskNo, "已自动重试");
                TaskRecord retry = resubmit(sessionNo, capability,
                        args == null ? Map.of() : args, actorId, traceId, task.skillId);
                if (isUsable(retry)) {
                    return RecoveryOutcome.recovered(retry, capability, "RETRIED");
                }
                current = retry;
                if (retry.error != null) {
                    error = retry.error;
                    category = error.category() == null ? "UNKNOWN" : error.category().toUpperCase();
                }
            }
            // 兜底反馈指向最后一次重试，避免 UI/人工队列指向已自动关闭的旧任务。
            task = current;
        }

        // LLM 不可用时，参数缺失仍然要“追问用户”，而不是直接失败/转人工。
        if ("VALIDATION".equals(category) && capability != null) {
            humanInterventionService.autoResolve(task.taskNo, "已转为追问用户，等待补充信息");
            return RecoveryOutcome.clarify(task, capability, translator.question(error),
                    capability.capabilityId(), args == null ? Map.of() : args, List.of());
        }

        // 到这一步不再把技术错误抛给用户：能明确是“无此能力”就明确说，否则转人工。
        String action = ("NOT_FOUND".equals(category)
                || "CAPABILITY_NOT_FOUND".equalsIgnoreCase(error.code()))
                ? "NO_CAPABILITY" : "TRANSFERRED";
        String message = translator.message(error);
        log.info("A2A 自修复兜底 traceId={} taskNo={} action={} category={}",
                traceId, task.taskNo, action, category);
        return RecoveryOutcome.fallback(task, capability, action, message);
    }

    private RecoveryOutcome applyDecision(String sessionNo, String userMessage, Capability capability,
                                          Map<String, Object> args, TaskRecord task,
                                          String actorId, String traceId, int depth,
                                          AgentError error, RecoveryDecision decision) {
        if (decision.isClarify()) {
            String pendingCapability = decision.capabilityId() != null
                    ? decision.capabilityId()
                    : (capability == null ? null : capability.capabilityId());
            Map<String, Object> pendingArgs = new LinkedHashMap<>(args == null ? Map.of() : args);
            if (decision.args() != null) {
                pendingArgs.putAll(decision.args());
            }
            String message = decision.clarification() == null || decision.clarification().isBlank()
                    ? translator.question(error)
                    : decision.clarification();
            humanInterventionService.autoResolve(task.taskNo, "已转为追问用户，等待补充信息");
            return RecoveryOutcome.clarify(task, capability, message,
                    pendingCapability, pendingArgs, decision.missingFields());
        }

        if (decision.canRetry()) {
            Capability target = registry.capability(decision.capabilityId()).orElse(null);
            if (target == null) {
                return RecoveryOutcome.fallback(task, capability, "NO_CAPABILITY",
                        translator.message(error));
            }
            Map<String, Object> merged = new LinkedHashMap<>(args == null ? Map.of() : args);
            if (decision.args() != null) {
                merged.putAll(decision.args());
            }
            humanInterventionService.autoResolve(task.taskNo, "已自动修复并重试");
            String retrySkillId = target.capabilityId().equals(task.capabilityId)
                    ? task.skillId : null;
            TaskRecord retry = resubmit(sessionNo, target, merged, actorId, traceId, retrySkillId);
            if (retry.status == TaskStatus.FAILED) {
                if (depth == 0) {
                    return recover(sessionNo, userMessage, target, merged, retry,
                            actorId, traceId, depth + 1);
                }
                AgentError retryError = retry.error == null ? error : retry.error;
                return RecoveryOutcome.fallback(retry, target, "TRANSFERRED",
                        translator.message(retryError));
            }
            String action = "ALTERNATIVE".equalsIgnoreCase(decision.action())
                    ? "ALTERNATIVE" : "RETRIED";
            return RecoveryOutcome.recovered(retry, target, action);
        }

        if (decision.isNoCapability()) {
            String message = decision.clarification() == null || decision.clarification().isBlank()
                    ? translator.message(error)
                    : decision.clarification();
            return RecoveryOutcome.fallback(task, capability, "NO_CAPABILITY", message);
        }
        return null;
    }

    private TaskRecord resubmit(String sessionNo, Capability capability, Map<String, Object> args,
                                String actorId, String traceId, String skillId) {
        // Skill 只在同一能力重试时保留；换能力时由上层重新选择，避免子 Agent 400。
        return skillId == null || skillId.isBlank()
                ? taskService.submit(sessionNo, capability, args, actorId, traceId)
                : taskService.submit(sessionNo, capability, args, actorId, traceId, skillId);
    }

    private static boolean isDecisionCategory(String category) {
        return "VALIDATION".equals(category) || "NOT_FOUND".equals(category)
                || "UNKNOWN".equals(category);
    }

    private static boolean isTransient(String category, AgentError error) {
        return error.retryable()
                || "DEPENDENCY".equals(category)
                || "TIMEOUT".equals(category)
                || "RATE_LIMIT".equals(category);
    }

    private static boolean isUsable(TaskRecord task) {
        return task != null && (task.status == TaskStatus.SUCCEEDED
                || task.status == TaskStatus.WAITING_CONFIRM
                || task.status == TaskStatus.PENDING
                || task.status == TaskStatus.RUNNING);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
