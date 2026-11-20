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

    private MainAgentTools() {
    }

    public static boolean isMainTool(String capabilityId) {
        return MEMORY_UPDATE.equals(capabilityId) || MEMORY_GET.equals(capabilityId);
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
                        "inputSchema", Map.of("type", "object")));
    }
}
