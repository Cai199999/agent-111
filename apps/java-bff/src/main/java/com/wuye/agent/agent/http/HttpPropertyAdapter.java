package com.wuye.agent.agent.http;

import com.wuye.agent.agent.model.AgentDescriptor;
import com.wuye.agent.agent.model.Capability;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 物业 Agent 真实 HTTP Adapter（property-v08）。
 * 通过 AGENT_PROPERTY_BASE_URL（默认 8402）接入，凭据来自环境变量。
 */
@Component
@ConditionalOnProperty(name = "wuye.agent.mode", havingValue = "http")
public class HttpPropertyAdapter extends AbstractHttpAdapter {

    private static final String AGENT_KEY = "property-maintenance";

    public HttpPropertyAdapter(HttpAgentSupport support, AgentHttpProperties properties) {
        super(support, properties.getProperty(), AgentDialect.PROPERTY, buildDescriptor());
    }

    private static AgentDescriptor buildDescriptor() {
        return new AgentDescriptor(
                AGENT_KEY,
                "物业运维 Agent",
                "property-v08",
                "0.8.0",
                "wrapped",
                "/agent/v1/health",
                "/agent/v1/ready",
                true,
                "db-recovery",
                List.of(
                        cap("property.ticket.query", "ticket.query", "查询工单", true, false, "SYNC", true),
                        cap("property.ticket.detail", "ticket.detail", "工单详情", true, false, "SYNC", true),
                        cap("property.ticket.similar", "ticket.similar", "相似工单", true, false, "SYNC", true),
                        cap("property.knowledge.search", "knowledge.search", "物业知识检索", true, false, "SYNC", true),
                        cap("property.masterdata.options", "masterdata.options", "主数据选项", true, false, "SYNC", true),
                        cap("property.profile.get", "profile.get", "读取个人资料", true, false, "SYNC", true),
                        cap("property.profile.update", "profile.update", "更新个人资料", false, true, "SYNC", true),
                        cap("property.ticket.create", "ticket.create", "创建工单", false, true, "SYNC", true),
                        cap("property.ticket.assign", "ticket.assign", "派单", false, true, "SYNC", true),
                        cap("property.ticket.accept", "ticket.accept", "接单", false, true, "SYNC", true),
                        cap("property.ticket.resolve", "ticket.resolve", "提交解决", false, true, "SYNC", true),
                        cap("property.ticket.verify", "ticket.verify", "验证", false, true, "SYNC", true),
                        cap("property.ticket.close", "ticket.close", "关闭", false, true, "SYNC", true),
                        cap("property.ticket.cancel", "ticket.cancel", "取消工单", false, true, "SYNC", true),
                        cap("property.ticket.hold", "ticket.hold", "挂起", false, true, "SYNC", true),
                        cap("property.ticket.resume", "ticket.resume", "恢复", false, true, "SYNC", true),
                        cap("property.ticket.finish", "ticket.finish", "结束", false, true, "SYNC", true),
                        cap("property.ticket.comment.add", "ticket.comment.add", "评论", false, true, "SYNC", true),
                        cap("property.ticket.material.add", "ticket.material.add", "材料", false, true, "SYNC", true),
                        cap("property.ticket.visit.add", "ticket.visit.add", "回访", false, true, "SYNC", true),
                        cap("property.ticket.export", "ticket.export", "导出 Excel", false, true, "ASYNC", true)
                ));
    }

    private static Capability cap(String id, String taskType, String name,
                                  boolean readOnly, boolean confirm, String mode, boolean enabled) {
        return new Capability(id, AGENT_KEY, taskType, "property", name, name,
                readOnly, confirm, List.of(mode), enabled);
    }
}
