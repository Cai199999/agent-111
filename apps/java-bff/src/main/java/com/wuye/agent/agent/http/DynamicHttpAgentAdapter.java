package com.wuye.agent.agent.http;

import com.wuye.agent.agent.model.AgentDescriptor;

/**
 * 运行时安装的外部 HTTP Agent。
 *
 * <p>只走 Canonical v1 的 HTTP 任务契约，不执行任意代码。
 * 描述信息和能力来自管理员安装时拉取的 Agent Card。</p>
 */
public class DynamicHttpAgentAdapter extends AbstractHttpAdapter {

    public DynamicHttpAgentAdapter(HttpAgentSupport support,
                                   AgentHttpProperties.Endpoint endpoint,
                                   AgentDescriptor descriptor) {
        super(support, endpoint, AgentDialect.CANONICAL, descriptor);
    }
}
