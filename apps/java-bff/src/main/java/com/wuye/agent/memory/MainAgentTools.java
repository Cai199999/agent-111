package com.wuye.agent.memory;

import java.util.List;
import java.util.Map;

/**
 * 主 Agent 自有工具，不属于任何子 Agent。
 * 用于显式记住/读取全局用户画像，避免误走子 Agent 的个人资料能力。
 */
public final class MainAgentTools {

    public static final String MEMORY_UPDATE = "memory.profile.update";
    public static final String MEMORY_GET = "memory.profile.get";
    public static final String PLATFORM_CATALOG = "platform.catalog";
    public static final String PLATFORM_A2A = "platform.a2a";
    public static final String PLATFORM_MEMORY = "platform.memory";
    public static final String BATCH_EXECUTE = "batch.execute";

    private MainAgentTools() {
    }

    public static boolean isMainTool(String capabilityId) {
        return MEMORY_UPDATE.equals(capabilityId)
                || MEMORY_GET.equals(capabilityId)
                || PLATFORM_CATALOG.equals(capabilityId)
                || PLATFORM_A2A.equals(capabilityId)
                || PLATFORM_MEMORY.equals(capabilityId)
                || BATCH_EXECUTE.equals(capabilityId);
    }

    public static List<Map<String, Object>> payload() {
        return List.of(
                Map.of(
                        "capabilityId", MEMORY_UPDATE,
                        "displayName", "记住用户偏好",
                        "description", "保存用户的稳定偏好（默认项目/楼栋/房间、常用工单类型/优先级、常用意图、备注）。"
                                + "这不是个人资料，不要处理姓名/电话等敏感信息。",
                        "readOnly", false,
                        "requiresConfirmation", false,
                        "executionModes", List.of("SYNC"),
                        "inputSchema", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "preferredProjectId", Map.of("type", "integer"),
                                        "preferredBuildingId", Map.of("type", "integer"),
                                        "preferredRoomId", Map.of("type", "integer"),
                                        "preferredTicketTypeCode", Map.of("type", "string"),
                                        "preferredPriorityCode", Map.of("type", "string"),
                                        "commonIntents", Map.of("type", "array",
                                                "items", Map.of("type", "string")),
                                        "notes", Map.of("type", "string"))),
                        "inputSchemaName", "memoryProfile"),
                Map.of(
                        "capabilityId", MEMORY_GET,
                        "displayName", "读取用户偏好",
                        "description", "读取当前用户的全局画像（稳定偏好，不含敏感个人信息）。",
                        "readOnly", true,
                        "requiresConfirmation", false,
                        "executionModes", List.of("SYNC"),
                        "inputSchema", Map.of("type", "object")),
                Map.of(
                        "capabilityId", PLATFORM_CATALOG,
                        "displayName", "读取平台 Agent 目录",
                        "description", "读取当前平台真实注册的 Agent、能力和角色可见性。"
                                + "用户问某个 Agent 的功能时，必须用 agentKey/keyword 过滤；"
                                + "不要默认返回全部 Agent。追问时沿用上下文里的 Agent。",
                        "readOnly", true,
                        "requiresConfirmation", false,
                        "executionModes", List.of("SYNC"),
                        "inputSchema", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "agentKey", Map.of("type", "string",
                                                "description", "如 geo-asset-agent / property-maintenance / contract-review"),
                                        "keyword", Map.of("type", "string",
                                                "description", "按 Agent 名称或能力关键词过滤，如 时空资产/地块/图斑")))),
                Map.of(
                        "capabilityId", PLATFORM_A2A,
                        "displayName", "读取 A2A 协议状态",
                        "description", "读取 A2A 协议、Agent Card 兼容状态、SSE/确认/JSON-RPC 支持和动态安装数量。",
                        "readOnly", true,
                        "requiresConfirmation", false,
                        "executionModes", List.of("SYNC"),
                        "inputSchema", Map.of("type", "object")),
                Map.of(
                        "capabilityId", PLATFORM_MEMORY,
                        "displayName", "读取主 Agent 记忆",
                        "description", "读取当前会话摘要 + 全局用户画像。用户问“记忆里有什么/你记得我什么”时使用。",
                        "readOnly", true,
                        "requiresConfirmation", false,
                        "executionModes", List.of("SYNC"),
                        "inputSchema", Map.of("type", "object")),
                Map.of(
                        "capabilityId", BATCH_EXECUTE,
                        "displayName", "批量执行",
                        "description", "当用户一次要求多个独立操作（不同工单/不同对象/不同处理人）时使用。"
                                + "items 每项一个操作，不要合并不同工单；每项 args 带 keyword/description "
                                + "和 assigneeName/assigneeId，让主 Agent 解析内部 ID。",
                        "readOnly", false,
                        "requiresConfirmation", false,
                        "executionModes", List.of("SYNC"),
                        "inputSchema", Map.of(
                                "type", "object",
                                "required", List.of("items"),
                                "properties", Map.of(
                                        "items", Map.of(
                                                "type", "array",
                                                "items", Map.of(
                                                        "type", "object",
                                                        "required", List.of("capabilityId"),
                                                        "properties", Map.of(
                                                                "capabilityId", Map.of("type", "string"),
                                                                "args", Map.of("type", "object")))))))
        );
    }
}
