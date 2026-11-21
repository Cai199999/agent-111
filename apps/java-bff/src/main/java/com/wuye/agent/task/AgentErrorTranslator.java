package com.wuye.agent.task;

import com.wuye.agent.agent.model.AgentError;
import java.util.Map;

import org.springframework.stereotype.Service;

/**
 * 把子 Agent 的技术错误翻译成用户能理解、能行动的中文反馈。
 * 技术错误仍保留在任务事件和审计里，不丢失排障信息。
 */
@Service
public class AgentErrorTranslator {

    private static final Map<String, String> FIELD_LABELS = Map.of(
            "ticketTypeCode", "工单类型",
            "priorityCode", "优先级",
            "locationText", "位置",
            "description", "报修内容",
            "ticketNo", "工单号",
            "assigneeId", "处理人",
            "contractNo", "合同号",
            "contractId", "合同ID",
            "contactName", "联系人");

    public String message(AgentError error) {
        String category = error == null || error.category() == null
                ? "UNKNOWN" : error.category().toUpperCase();
        return switch (category) {
            case "AUTH" -> "当前账号没有这个权限，请联系管理员，或换有权限的账号再试。";
            case "DEPENDENCY" -> "负责处理这个任务的子 Agent 暂时不可用，我已经重试过了。"
                    + "你可以稍后再试，或者我帮你转人工跟进。";
            case "TIMEOUT" -> "这次处理超时了，我已经帮你转人工跟进，稍后可以查看处理结果。";
            case "RATE_LIMIT" -> "请求有点频繁，请稍等一会儿再试。";
            case "CONFLICT" -> "任务状态已经变化，请刷新页面后重试。";
            case "NOT_FOUND" -> "目前没有这个功能。我可以帮你查工单、建单、派单，或审查合同。";
            case "VALIDATION" -> question(error);
            default -> "这次没有处理成功，我已经记录了问题，并转人工跟进。";
        };
    }

    public String question(AgentError error) {
        String detail = error == null || error.message() == null ? "" : error.message().strip();
        if (detail.isBlank()) {
            return "我还需要确认一些关键信息，能再具体补充一下吗？";
        }
        return "我还需要确认一下：" + humanize(detail) + "。能补充一下吗？";
    }

    /** 把 ticketTypeCode 之类的字段名换成用户看得懂的中文。 */
    private String humanize(String text) {
        String result = text;
        for (Map.Entry<String, String> entry : FIELD_LABELS.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }
}
