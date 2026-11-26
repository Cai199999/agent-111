package com.wuye.agent.routing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * 规则优先的路由器。原型不依赖 LLM，先把“明确意图 -> capabilityId”跑通。
 *
 * <p>生产化后规则仍作为第一层，LLM 只在规则未命中或低置信度时兜底；
 * LLM 只输出 JSON 决策，不直接执行工具。</p>
 */
@Component
public class RuleRouter implements Router {

    private static final Pattern TICKET_NO = Pattern.compile("GD\\d{6,}");
    /** 真实 0.11.0 的写能力按 ticketId（数字）寻址。 */
    private static final Pattern TICKET_ID = Pattern.compile(
            "(?:工单\\s*(?:ID|id|编号|id号)|ticketId)\\s*[:：=]?\\s*(\\d{4,})");
    private static final Pattern CONTRACT_NO = Pattern.compile("HT[-A-Za-z0-9]+");
    private static final Pattern ASSIGNEE = Pattern.compile("(?:派给|分配给|给)\\s*(\\d{3,})");
    private static final Pattern CONTRACT_ID = Pattern.compile("(\\d{4,})");
    private static final Pattern LOCATION = Pattern.compile("(\\d+栋[^，,。\\s]*)");
    private static final Pattern PARCEL_NO = Pattern.compile("P-\\d{4}-\\d+");

    public RouteDecision route(String raw) {
        String text = raw == null ? "" : raw.strip();
        String lower = text.toLowerCase();
        String compact = lower.replaceAll("\\s+", "");

        boolean knowledge = containsAny(text, "知识库", "知识", "资料", "制度", "规定");
        boolean contract = containsAny(text, "合同", "条款", "审查", "甲方", "乙方", "违约");
        boolean property = containsAny(text, "工单", "报修", "派单", "接单", "现场", "回访", "维修", "漏水", "故障", "待处理");

        // 0. 平台自省：面试官问“平台有什么/怎么接”时，返回真实注册表数据。
        // 先处理“某个 Agent 有什么功能”，避免只返回全部目录。
        if (containsAny(text, "时空资产")
                && !containsAny(text, "地块", "图斑", "巡查", "遥感", "变化")) {
            return RouteDecision.route("platform.catalog",
                    Map.of("agentKey", "geo-asset-agent"), 0.95, "时空资产能力查询");
        }
        if (containsAny(text, "物业")
                && containsAny(text, "功能", "能力", "有什么", "能做什么")
                && !containsAny(text, "工单", "报修")) {
            return RouteDecision.route("platform.catalog",
                    Map.of("agentKey", "property-maintenance"), 0.95, "物业能力查询");
        }
        if (containsAny(text, "合同")
                && containsAny(text, "功能", "能力", "有什么")
                && !containsAny(text, "审查", "解析", "规则")) {
            return RouteDecision.route("platform.catalog",
                    Map.of("agentKey", "contract-review"), 0.95, "合同能力查询");
        }
        if (containsAny(text, "具体是什么功能", "全部功能", "所有功能",
                "功能列出来", "详细功能")) {
            return RouteDecision.route("platform.catalog", Map.of(), 0.95, "平台能力追问");
        }
        if (containsAny(compact, "哪些agent", "有哪些agent", "agent目录", "平台上有",
                "平台能力", "能做什么", "agent列表")) {
            return RouteDecision.route("platform.catalog", Map.of(), 0.95, "平台目录自省");
        }
        if (lower.contains("a2a") || containsAny(compact, "agentcard", "协议支持", "协议兼容")) {
            return RouteDecision.route("platform.a2a", Map.of(), 0.95, "A2A 协议自省");
        }
        if (containsAny(text, "记忆里", "我的画像", "记得我", "记忆系统", "记住我什么")) {
            return RouteDecision.route("platform.memory", Map.of(), 0.95, "记忆层自省");
        }

        // 1. 知识检索：两域同名能力，必须先消歧
        if (knowledge) {
            if (containsAny(text, "合同", "条款", "违约")) {
                return RouteDecision.route("contract.knowledge.search",
                        Map.of("query", text, "topK", 3), 0.9, "命中合同知识检索");
            }
            if (containsAny(text, "物业", "工单", "报修", "维修")) {
                return RouteDecision.route("property.knowledge.search",
                        Map.of("query", text, "topK", 3), 0.9, "命中物业知识检索");
            }
            return RouteDecision.clarify("你要查物业知识库，还是合同知识库？", "知识检索域不明确");
        }

        // 2. 合同域
        if (contract) {
            if (containsAny(text, "创建规则", "新增规则", "加一条规则", "建规则")) {
                return RouteDecision.route("contract.rule.create",
                        Map.of("ruleName", text), 0.86, "命中创建合同规则");
            }
            if (containsAny(text, "规则") && !containsAny(text, "审查合同")) {
                return RouteDecision.route("contract.rule.list", Map.of(), 0.9, "命中合同规则查询");
            }
            // 注意：必须先判断“审查”，否则“审查合同”会被“查合同”子串误命中为 resolve
            if (containsAny(text, "审查", "审核", "审一下", "帮我审")) {
                Matcher contractNo = CONTRACT_NO.matcher(text);
                if (contractNo.find()) {
                    return RouteDecision.route("contract.review",
                            Map.of("contractNo", contractNo.group()), 0.93, "命中合同审查+合同号");
                }
                Matcher id = CONTRACT_ID.matcher(text);
                if (id.find()) {
                    return RouteDecision.route("contract.review",
                            Map.of("contractId", Long.parseLong(id.group(1))), 0.93, "命中合同审查+合同ID");
                }
                // 用户只给了合同名称（如“物业服务合同”）：先 resolve 再 review，不要求编造 ID。
                return new RouteDecision("contract.review", Map.of(), 0.85, false, null,
                        "按合同名称先解析再审查",
                        List.of(new RouteDecision.PlanStep("contract.resolve", Map.of("query", text)),
                                new RouteDecision.PlanStep("contract.review", Map.of())),
                        List.of(), "RULE", null);
            }
            if (containsAny(text, "解析", "查找合同", "查合同", "定位合同")) {
                return RouteDecision.route("contract.resolve",
                        Map.of("query", text), 0.85, "命中合同解析");
            }
            if (containsAny(text, "合同")) {
                return RouteDecision.route("contract.resolve",
                        Map.of("query", text), 0.7, "合同域兜底解析");
            }
        }

        // 3. 运行时安装的企业 Agent 示例（未安装时由 ChatService 做友好兜底）
        if (containsAny(text, "政策", "法规", "耕地", "条例", "补贴")) {
            if (containsAny(text, "审查", "审核") && containsAny(text, "文件", "政策")) {
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("fileName", text);
                args.put("scope", "全部条款");
                return RouteDecision.route("policy.document.review", args, 0.9, "命中政策文件审查");
            }
            return RouteDecision.route("policy.qa.ask",
                    Map.of("question", text), 0.9, "命中政策法规问答");
        }
        if (containsAny(text, "客服", "投诉", "客户工单")) {
            if (containsAny(text, "创建", "新建", "提交", "投诉")) {
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("customer", "未提供");
                args.put("issue", text);
                args.put("priority", "NORMAL");
                return RouteDecision.route("customer.ticket.create", args, 0.9, "命中客服工单创建");
            }
            return RouteDecision.route("customer.ticket.query",
                    Map.of("query", text), 0.9, "命中客服工单查询");
        }

        // 4. 运行时安装的巡检报告 Agent（未安装时由 ChatService 做友好兜底）
        if (containsAny(text, "巡检")) {
            if (containsAny(text, "导出")) {
                return RouteDecision.route("inspect.report.export",
                        Map.of("query", text), 0.9, "命中巡检报告导出");
            }
            if (containsAny(text, "生成", "创建", "新建") && text.contains("报告")) {
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("reportType", text);
                args.put("region", "全部区域");
                args.put("period", "最近 7 天");
                return RouteDecision.route("inspect.report.generate", args, 0.9, "命中巡检报告生成");
            }
            if (containsAny(text, "计划", "安排")) {
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("planName", text);
                args.put("region", "全部区域");
                return RouteDecision.route("inspect.plan.create", args, 0.9, "命中巡检计划");
            }
            return RouteDecision.route("inspect.report.query",
                    Map.of("query", text), 0.9, "命中巡检报告查询");
        }

        // 5. 时空资产域（第三个演示 Agent）
        Matcher parcel = PARCEL_NO.matcher(text);
        String parcelNo = parcel.find() ? parcel.group() : null;
        boolean patrolIntent = containsAny(text, "巡查", "执法", "外业")
                || (containsAny(text, "核查", "复核")
                    && containsAny(text, "创建", "新建", "派发", "安排", "任务", "图斑", "地块"));
        if (patrolIntent) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("patrolType", text);
            if (parcelNo != null) {
                args.put("parcelNo", parcelNo);
                return RouteDecision.route("gis.patrol.create", args, 0.9, "命中巡查任务");
            }
            // 用户只说了地块名称：先查唯一候选，再创建巡查任务。
            return new RouteDecision("gis.patrol.create", args, 0.85, false, null,
                    "按地块名称先解析再创建巡查",
                    List.of(new RouteDecision.PlanStep("gis.parcel.query",
                                    Map.of("keyword", text)),
                            new RouteDecision.PlanStep("gis.patrol.create", args)),
                    List.of(), "RULE", null);
        }
        if (containsAny(text, "遥感", "变化图斑", "图斑", "影像", "变化检测")) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("keyword", text);
            if (parcelNo != null) {
                args.put("parcelNo", parcelNo);
            }
            return RouteDecision.route("gis.change.query", args, 0.9, "命中变化图斑查询");
        }
        if (containsAny(text, "地块", "宗地", "地籍", "不动产单元")) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("keyword", text);
            if (parcelNo != null) {
                args.put("parcelNo", parcelNo);
            }
            return RouteDecision.route("gis.parcel.query", args, 0.9, "命中地块查询");
        }

        // 6. 物业域
        Matcher ticket = TICKET_NO.matcher(text);
        String ticketNo = ticket.find() ? ticket.group() : null;
        Matcher ticketIdMatcher = TICKET_ID.matcher(text);
        Long ticketId = ticketIdMatcher.find()
                ? parseLong(ticketIdMatcher.group(1)) : null;

        // 一次派多个工单：拆成 batch.execute，每项一个 assign。
        if (countOccurrences(text, "派给") >= 2 || countOccurrences(text, "分配给") >= 2) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (String clause : text.split("[，,；;]")) {
                if (!clause.contains("派给") && !clause.contains("分配给")) {
                    continue;
                }
                Map<String, Object> args = new LinkedHashMap<>();
                Matcher clauseTicketId = TICKET_ID.matcher(clause);
                if (clauseTicketId.find()) {
                    args.put("ticketId", parseLong(clauseTicketId.group(1)));
                } else {
                    args.put("keyword", clause);
                }
                Matcher clauseAssignee = ASSIGNEE.matcher(clause);
                String worker = clauseAssignee.find()
                        ? clauseAssignee.group(1) : inferWorker(clause);
                if (worker != null) {
                    args.put("assigneeId", worker);
                }
                items.add(Map.of(
                        "capabilityId", "property.ticket.assign",
                        "args", args));
            }
            if (items.size() > 1) {
                return RouteDecision.route("batch.execute", Map.of("items", items),
                        0.9, "命中批量派单");
            }
        }

        if (containsAny(text, "派单", "派给", "分配给") || (ticketNo != null && text.contains("派"))) {
            Matcher assignee = ASSIGNEE.matcher(text);
            String assigneeId = assignee.find() ? assignee.group(1) : inferWorker(text);
            Map<String, Object> finalArgs = new LinkedHashMap<>();
            if (assigneeId != null) {
                finalArgs.put("assigneeId", assigneeId);
            }
            if (ticketId != null) {
                finalArgs.put("ticketId", ticketId);
                if (assigneeId == null) {
                    return RouteDecision.clarify(
                            "请说明派给谁，例如“派给张师傅”或“派给 10002”。", "派单缺少处理人");
                }
                return RouteDecision.route("property.ticket.assign", finalArgs, 0.95,
                        "命中派单+工单ID");
            }
            if (ticketNo != null) {
                finalArgs.put("ticketNo", ticketNo);
                if (assigneeId == null) {
                    return RouteDecision.clarify(
                            "请说明派给谁，例如“派给张师傅”或“派给 10002”。", "派单缺少处理人");
                }
                return RouteDecision.route("property.ticket.assign", finalArgs, 0.95, "命中派单");
            }
            // 用户只描述了工单（如“3栋2单元漏水那个工单”）：先查唯一候选，再派单。
            if (containsAny(text, "工单", "漏水", "故障", "照明", "道闸", "维修", "报修")) {
                return new RouteDecision("property.ticket.assign", finalArgs, 0.85, false, null,
                        "按描述先查工单再派单",
                        List.of(new RouteDecision.PlanStep("property.ticket.query",
                                        Map.of("keyword", text)),
                                new RouteDecision.PlanStep("property.ticket.assign", finalArgs)),
                        List.of(), "RULE", null);
            }
            return RouteDecision.clarify("请说明要派哪张工单，例如“3栋2单元漏水那个工单”。",
                    "派单缺少工单描述");
        }

        // 0.11.0：AI 工单分析是低风险写，requiresConfirmation=false，直接执行。
        if ((containsAny(text, "ai分析", "AI 分析", "智能分析", "分析工单")
                || (text.contains("分析") && ticketNo != null))
                && (ticketNo != null || text.contains("工单"))) {
            Map<String, Object> args = new LinkedHashMap<>();
            if (ticketNo != null) {
                args.put("ticketNo", ticketNo);
            }
            return RouteDecision.route("property.ticket.ai.analyze", args, 0.9,
                    "命中 AI 工单分析");
        }

        // 工单生命周期动作：用户可只描述工单，ticketNo 交给主 Agent 语义解析。
        if (containsAny(text, "关闭", "关掉", "关单")) {
            return ticketAction("property.ticket.close", ticketNo, ticketId);
        }
        if (containsAny(text, "接单")) {
            return ticketAction("property.ticket.accept", ticketNo, ticketId);
        }
        if (containsAny(text, "解决", "修好", "处理完")) {
            return ticketAction("property.ticket.resolve", ticketNo, ticketId);
        }
        if (containsAny(text, "验证")) {
            return ticketAction("property.ticket.verify", ticketNo, ticketId);
        }
        if (containsAny(text, "挂起", "暂停")) {
            return ticketAction("property.ticket.hold", ticketNo, ticketId);
        }
        if (containsAny(text, "恢复")) {
            return ticketAction("property.ticket.resume", ticketNo, ticketId);
        }
        if (containsAny(text, "结束工单")) {
            return ticketAction("property.ticket.finish", ticketNo, ticketId);
        }
        if (containsAny(text, "取消工单")) {
            return ticketAction("property.ticket.cancel", ticketNo, ticketId);
        }
        if (containsAny(text, "回访")) {
            return ticketAction("property.ticket.visit.add", ticketNo, ticketId);
        }
        if (containsAny(text, "评论", "备注")) {
            return ticketAction("property.ticket.comment.add", ticketNo, ticketId);
        }
        if (containsAny(text, "详情", "详细")) {
            return ticketAction("property.ticket.detail", ticketNo, ticketId);
        }

        // 注意：必须先判断“创建/新建 + 工单”，否则只命中“工单”会落到查询
        boolean createIntent = containsAny(text, "建单", "创建工单", "新建工单",
                "新建一张工单", "建一张工单", "创建一个工单", "新建一个工单",
                "开单", "报修", "报障", "漏水", "维修", "坏了", "故障");
        if (createIntent) {
            if (isGenericCreate(text)) {
                return RouteDecision.clarify(
                        "请描述报修内容和位置，例如：3栋2单元水管漏水。",
                        "建单缺少描述");
            }
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("description", text);
            Matcher location = LOCATION.matcher(text);
            if (location.find()) {
                args.put("locationText", location.group(1));
            }
            // 规则兜底也做最小枚举映射；映射不出来就不猜，交给 schema 校验触发追问。
            String ticketType = inferTicketType(text);
            if (ticketType != null) {
                args.put("ticketTypeCode", ticketType);
            }
            return RouteDecision.route("property.ticket.create", args, 0.9, "命中建单/报修");
        }

        if (ticketId != null) {
            return RouteDecision.route("property.ticket.detail",
                    Map.of("ticketId", ticketId), 0.94, "命中工单ID详情");
        }
        if (ticketNo != null) {
            if (containsAny(text, "详情", "详细", "看看", "查")) {
                return RouteDecision.route("property.ticket.detail", Map.of("ticketNo", ticketNo), 0.94, "命中工单详情");
            }
            return RouteDecision.route("property.ticket.detail", Map.of("ticketNo", ticketNo), 0.88, "根据工单号查详情");
        }

        if (property || containsAny(text, "我的工单", "待处理")) {
            return RouteDecision.route("property.ticket.query",
                    Map.of("status", "PENDING", "limit", 5), 0.9, "命中工单查询");
        }

        return RouteDecision.clarify(
                "我可以帮你查工单、建单、派单，或查合同规则、审查合同。请再说具体一点。",
                "未命中明确意图");
    }

    public static boolean isGenericCreate(String text) {
        String normalized = text.replaceAll("[\\s，。！？!?：:、]", "");
        return normalized.equals("创建工单")
                || normalized.equals("新建工单")
                || normalized.equals("新建一张工单")
                || normalized.equals("创建一个工单")
                || normalized.equals("新建一个工单")
                || normalized.equals("建一张工单")
                || normalized.equals("建单")
                || normalized.equals("开单")
                || normalized.equals("报修")
                || normalized.equals("帮我建单")
                || normalized.equals("帮我创建工单");
    }

    /** 工单动作：有 ticketId/ticketNo 直接执行，没有则触发主 Agent 的只读语义解析。 */
    private static RouteDecision ticketAction(String capabilityId, String ticketNo, Long ticketId) {
        Map<String, Object> args = new LinkedHashMap<>();
        if (ticketId != null) {
            args.put("ticketId", ticketId);
        }
        if (ticketNo != null) {
            args.put("ticketNo", ticketNo);
        }
        if (args.isEmpty()) {
            return new RouteDecision(capabilityId, Map.of(), 0.85, true,
                    "请描述要处理的工单，例如“3栋2单元漏水那个工单”。",
                    "命中工单操作", List.of(), List.of("ticketNo"), "RULE", null);
        }
        return RouteDecision.route(capabilityId, args, 0.9,
                ticketId != null ? "命中工单操作+工单ID" : "命中工单操作");
    }

    private static Long parseLong(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** 规则兜底的人名映射；LLM 正常会通过 optionCatalog.workers 自动映射。 */
    private static String inferWorker(String text) {
        if (text.contains("张师傅")) {
            return "10002";
        }
        if (text.contains("李师傅")) {
            return "10003";
        }
        return null;
    }

    /** 规则路由的最小枚举映射；不确定时返回 null，让 schema 校验走“追问用户”。 */
    private static String inferTicketType(String text) {
        if (containsAny(text, "灯", "照明", "不亮")) {
            return "LIGHTING";
        }
        if (containsAny(text, "水", "漏水", "管", "泵", "堵")) {
            return "PLUMBING";
        }
        if (containsAny(text, "电梯", "升降")) {
            return "ELEVATOR";
        }
        if (containsAny(text, "门禁", "安防", "监控", "道闸")) {
            return "SECURITY";
        }
        if (containsAny(text, "保洁", "垃圾", "卫生", "清扫")) {
            return "CLEANING";
        }
        return null;
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
