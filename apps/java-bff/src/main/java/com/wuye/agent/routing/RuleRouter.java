package com.wuye.agent.routing;

import java.util.LinkedHashMap;
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
    private static final Pattern CONTRACT_NO = Pattern.compile("HT[-A-Za-z0-9]+");
    private static final Pattern ASSIGNEE = Pattern.compile("(?:派给|分配给|给)\\s*(\\d{3,})");
    private static final Pattern CONTRACT_ID = Pattern.compile("(\\d{4,})");
    private static final Pattern LOCATION = Pattern.compile("(\\d+栋[^，,。\\s]*)");
    private static final Pattern PARCEL_NO = Pattern.compile("P-\\d{4}-\\d+");

    public RouteDecision route(String raw) {
        String text = raw == null ? "" : raw.strip();
        String lower = text.toLowerCase();

        boolean knowledge = containsAny(text, "知识库", "知识", "资料", "制度", "规定");
        boolean contract = containsAny(text, "合同", "条款", "审查", "甲方", "乙方", "违约");
        boolean property = containsAny(text, "工单", "报修", "派单", "接单", "现场", "回访", "维修", "漏水", "故障", "待处理");

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
            if (containsAny(text, "审查")) {
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
                return RouteDecision.clarify("请提供要审查的合同编号或合同 ID。", "合同审查缺少合同标识");
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

        // 3. 运行时安装的巡检报告 Agent（未安装时由 ChatService 做友好兜底）
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

        // 4. 时空资产域（第三个演示 Agent）
        Matcher parcel = PARCEL_NO.matcher(text);
        String parcelNo = parcel.find() ? parcel.group() : null;
        boolean patrolIntent = containsAny(text, "巡查", "执法", "外业")
                || (text.contains("核查") && containsAny(text, "创建", "新建", "派发", "安排", "任务"));
        if (patrolIntent) {
            Map<String, Object> args = new LinkedHashMap<>();
            if (parcelNo != null) {
                args.put("parcelNo", parcelNo);
            }
            args.put("patrolType", text);
            return RouteDecision.route("gis.patrol.create", args, 0.9, "命中巡查任务");
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

        // 5. 物业域
        Matcher ticket = TICKET_NO.matcher(text);
        String ticketNo = ticket.find() ? ticket.group() : null;

        if (containsAny(text, "派单", "派给", "分配给") || (ticketNo != null && text.contains("派"))) {
            Matcher assignee = ASSIGNEE.matcher(text);
            if (ticketNo == null) {
                return RouteDecision.clarify("请提供要派单的工单号，例如 GD20261119000107。", "派单缺少工单号");
            }
            if (!assignee.find()) {
                return RouteDecision.clarify("请提供处理人 ID，例如“派给 10002”。", "派单缺少处理人");
            }
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("ticketNo", ticketNo);
            args.put("assigneeId", assignee.group(1));
            return RouteDecision.route("property.ticket.assign", args, 0.95, "命中派单");
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

    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
