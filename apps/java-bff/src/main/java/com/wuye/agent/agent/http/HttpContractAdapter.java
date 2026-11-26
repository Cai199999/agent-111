package com.wuye.agent.agent.http;

import com.wuye.agent.agent.model.AgentDescriptor;
import com.wuye.agent.agent.model.Capability;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 合同 Agent 真实 HTTP Adapter（contract-v08 / 0.8.0）。
 * 通过 AGENT_CONTRACT_BASE_URL（默认 8400）接入；写能力依赖委托令牌签发。
 */
@Component
@ConditionalOnProperty(name = "wuye.agent.mode", havingValue = "http")
public class HttpContractAdapter extends AbstractHttpAdapter {

    private static final String AGENT_KEY = "contract-review";

    public HttpContractAdapter(HttpAgentSupport support, AgentHttpProperties properties) {
        super(support, properties.getContract(), AgentDialect.CONTRACT, buildDescriptor());
    }

    private static AgentDescriptor buildDescriptor() {
        return new AgentDescriptor(
                AGENT_KEY,
                "合同审查 Agent",
                "contract-v08",
                "0.8.0",
                "raw",
                "/health",
                "/ready",
                true,
                "startup-recovery",
                List.of(
                        cap("contract.rule.list", "contract.rule.list", "查询审查规则", true, false, "SYNC", true),
                        cap("contract.knowledge.search", "knowledge.search", "合同知识检索", true, false, "SYNC", true),
                        cap("contract.resolve", "contract.resolve", "解析合同", true, false, "SYNC", true),
                        cap("contract.review", "contract.review", "合同审查", true, false, "ASYNC", true),
                        cap("contract.rule.create", "contract.rule.create", "创建审查规则", false, true, "ASYNC", true),
                        cap("contract.import", "contract.import", "导入合同（docx）", false, true, "ASYNC", true),
                        cap("external.legal.search", "external.legal.search", "法规检索（未接入）", true, false, "SYNC", false),
                        cap("external.counterparty.lookup", "external.counterparty.lookup", "工商/司法查询（未接入）", true, false, "SYNC", false),
                        cap("external.ocr.extract", "external.ocr.extract", "OCR（未接入）", true, false, "SYNC", false)
                ));
    }

    private static Capability cap(String id, String taskType, String name,
                                  boolean readOnly, boolean confirm, String mode, boolean enabled) {
        return new Capability(id, AGENT_KEY, taskType, "contract", name, name,
                readOnly, confirm, List.of(mode), enabled);
    }
}
