package com.wuye.agent.agent.adapter;

import com.wuye.agent.agent.model.AdapterResult;
import com.wuye.agent.agent.model.AdapterTaskRequest;
import com.wuye.agent.agent.model.AgentDescriptor;
import com.wuye.agent.agent.model.AgentHealth;
import com.wuye.agent.agent.model.Decision;
import com.wuye.agent.agent.model.TaskEvent;
import java.util.List;

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

    AdapterResult confirm(String childTaskId, String token, Decision decision, String comment);

    AdapterResult cancel(String childTaskId, String reason);

    List<TaskEvent> events(String childTaskId, long afterSeq);

    AgentHealth health();
}
