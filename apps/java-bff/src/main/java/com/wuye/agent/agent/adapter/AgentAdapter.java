package com.wuye.agent.agent.adapter;

import com.wuye.agent.agent.model.AdapterResult;
import com.wuye.agent.agent.model.AdapterTaskRequest;
import com.wuye.agent.agent.model.AgentDescriptor;
import com.wuye.agent.agent.model.AgentHealth;
import com.wuye.agent.agent.model.Decision;
import com.wuye.agent.agent.model.TaskEvent;
import java.util.List;
import java.util.Map;

/**
 * 子 Agent 协议适配 SPI。
 *
 * <p>业务层只依赖本接口；方言差异（包络、错误码、事件字段、确认体）全部由实现吸收。
 * 新标准 Agent 可实现 Canonical v1 Adapter；方言 Agent 各写一个实现。</p>
 */
public interface AgentAdapter {

    AgentDescriptor descriptor();

    AdapterResult submit(AdapterTaskRequest request);

    AdapterResult getTask(String childTaskId);

    /**
     * 带真实 Actor/Trace 的查询。默认回退到旧方法，方言 Adapter 可按需覆盖，
     * 避免后续调用使用配置里的固定 Actor，导致子 Agent 审计/授权失真。
     */
    default AdapterResult getTask(String childTaskId, String actorId, String traceId) {
        return getTask(childTaskId);
    }

    AdapterResult confirm(String childTaskId, String token, Decision decision, String comment);

    default AdapterResult confirm(String childTaskId, String token, Decision decision,
                                  String comment, String actorId, String traceId) {
        return confirm(childTaskId, token, decision, comment);
    }

    AdapterResult cancel(String childTaskId, String reason);

    default AdapterResult cancel(String childTaskId, String reason,
                                 String actorId, String traceId) {
        return cancel(childTaskId, reason);
    }

    List<TaskEvent> events(String childTaskId, long afterSeq);

    AgentHealth health();

    /**
     * 可选 Manifest：用于个人中心等通用表单聚合。
     * 默认返回空，表示该 Agent 不声明表单；HTTP Adapter 会拉取 /agent/v1/manifest。
     */
    default Map<String, Object> manifest() {
        return Map.of();
    }
}
