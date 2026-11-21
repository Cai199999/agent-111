"""路由决策：LLM 只输出 capabilityId/args/confidence，不执行任何工具。"""

from __future__ import annotations

import json
from typing import Any

from app.core.config import Settings
from app.llm.client import DeepSeekClient
from app.schemas import (
    CapabilityIn,
    PlanStep,
    RecoveryRequest,
    RecoveryResponse,
    RepairRequest,
    RepairResponse,
    RouteRequest,
    RouteResponse,
)

SYSTEM_PROMPT = """你是"物业主 Agent"的任务规划与路由决策器。
你只能从给定的 capabilities 中选择 capabilityId，不能编造能力。
你必须只输出一个 JSON 对象，不要输出 Markdown、解释或多余文字。

JSON 字段：
- capabilityId: string|null，最终要执行的能力
- intent: string|null，简短意图名
- args: object，最终能力的参数
- confidence: number，0 到 1
- needClarification: boolean，是否必须先追问用户
- clarification: string|null，追问内容，中文、简短
- missingFields: string[]，最终能力还缺哪些必填字段
- reason: string|null，路由原因
- plan: [{capabilityId, args}]，最多 3 步；最后一步必须是最终能力；
        前面的步骤只能是 readOnly=true 的查询/读取步骤，用于补齐最终参数。

规则：
1) 用户问题跨域（例如只说"知识库"）时，capabilityId=null，needClarification=true，追问是物业还是合同。
2) 写操作仍然只做路由和参数准备，最终由主 Agent 让用户确认。
3) 不要输出工具调用、代码、SQL 或任何执行指令。
4) 不确定时宁可追问，不要猜错域或编造 ID。
5) 必须检查能力 inputSchema.required：缺必填字段时写入 missingFields 并追问，不要猜。
6) 可以拆分任务：例如建单前先 profile.get 读取项目/楼宇/房间，再 ticket.create；
   但不要拆出无意义的步骤。
7) 如果 optionCatalog 提供了对应枚举（如 ticketTypeCode 对应 ticketTypes、
   priorityCode 对应 priorities、locationType 对应 locationTypes、
   indoorPublicArea 对应 indoorPublicAreas），必须使用 optionCatalog 里的 code；
   用户用中文描述时映射到最匹配的 name，无法确定时追问候选值，不要猜。
8) 如果输入里有 pending（上一轮追问状态），当前用户消息很可能是对追问的回答：
   把回答合并进 pending.args，继续检查 missingFields；
   不要重复询问已经回答过的字段；如果用户明显切换话题，则按新话题处理。
9) sessionSummary 只代表当前会话的摘要，只能用于当前会话上下文，不要跨会话推断。
10) userProfile 是用户的全局稳定偏好（如默认项目/楼栋/房间、常用工单类型/优先级），
    可以作为默认值；但当前消息明确表达的内容优先于画像。
    画像不含敏感个人信息，不要把画像内容当作事实对外复述。"""

RECOVERY_PROMPT = """你是"物业主 Agent"的 A2A 自修复决策器。
一次子 Agent 调用失败了。请根据用户原始请求、失败能力、参数和错误，决定下一步。
你只能从给定的 capabilities 中选择 capabilityId，不能编造能力。
你必须只输出一个 JSON 对象，不要输出 Markdown、解释或多余文字。

动作（action）只能是：
- CLARIFY：缺少关键信息，或需要用户确认。给出中文 clarification 和 missingFields，不要猜。
- RETRY：参数可修正，给出 capabilityId 和修正后的 args，由主 Agent 重试一次。
- ALTERNATIVE：当前能力不合适，换一个更合适的能力，给出 capabilityId 和 args。
- NO_CAPABILITY：现有能力都无法满足，明确兜底，不要假装成功。

JSON 字段：
- action: CLARIFY | RETRY | ALTERNATIVE | NO_CAPABILITY
- capabilityId: string|null
- args: object
- clarification: string|null，中文、简短、面向用户
- missingFields: string[]
- reason: string|null

规则：
1) 只能选 capabilities 里的 capabilityId，不能编造。
2) 不知道的关键 ID/枚举必须 CLARIFY，不能猜。
3) 写操作仍然只做参数准备，由主 Agent 让用户确认；不要在这里执行。
4) VALIDATION 错误优先 RETRY；如果缺必填字段，CLARIFY。
5) NOT_FOUND / CAPABILITY_NOT_FOUND 优先 ALTERNATIVE；没有合适能力则 NO_CAPABILITY。
6) DEPENDENCY / TIMEOUT / RATE_LIMIT 不要假装成功；可以 CLARIFY 告知稍后重试，或 ALTERNATIVE。
7) 如果用户请求超出所有能力范围，才用 NO_CAPABILITY，并明确说"目前没有这个功能"。
8) 只输出 JSON。"""

REPAIR_PROMPT = """你是"物业主 Agent"的参数修正器。
子 Agent 拒绝了上一次调用。请根据能力 inputSchema、原始参数和错误信息，
修正参数并只输出 JSON：
- capabilityId: string
- args: object
- needClarification: boolean
- clarification: string|null
- reason: string|null

规则：
1) 不要编造用户未提供、也无法从上下文推断的 ID/枚举值。
2) 缺少必填字段且无法推断时，needClarification=true，并明确问用户。
3) 不要改变 capabilityId，除非错误表明该能力不存在。
4) 只输出 JSON。"""


class RouteService:
    def __init__(self, client: DeepSeekClient, settings: Settings) -> None:
        self.client = client
        self.settings = settings

    def route(self, request: RouteRequest) -> RouteResponse:
        allowed = {cap.capabilityId: cap for cap in request.capabilities}
        user_prompt = build_user_prompt(request)
        data, usage = self.client.chat_json(SYSTEM_PROMPT, user_prompt)

        capability_id = _as_text(data.get("capabilityId"))
        if capability_id is not None and capability_id not in allowed:
            capability_id = None
            data["needClarification"] = True
            data.setdefault("clarification", "这个能力当前不可用，请换一种说法。")

        confidence = _clamp_float(data.get("confidence"), 0.0)
        need_clarification = bool(data.get("needClarification", False))
        clarification = _as_text(data.get("clarification"))
        if confidence < self.settings.confidence_threshold:
            need_clarification = True
            if clarification is None:
                clarification = "我不太确定你指的是哪类操作，能再说具体一点吗？"

        args = data.get("args")
        if not isinstance(args, dict):
            args = {}

        missing_fields = data.get("missingFields")
        if not isinstance(missing_fields, list):
            missing_fields = []
        missing_fields = [str(item) for item in missing_fields if str(item).strip()]
        if missing_fields:
            need_clarification = True
            if clarification is None:
                clarification = "还需要补充：" + "、".join(missing_fields)

        plan = _parse_plan(data.get("plan"), allowed, capability_id, args)
        return RouteResponse(
            intent=_as_text(data.get("intent")),
            capabilityId=capability_id,
            args=args,
            confidence=confidence,
            needClarification=need_clarification,
            clarification=clarification,
            reason=_as_text(data.get("reason")),
            plan=plan,
            missingFields=missing_fields,
            usage=usage,
        )

    def repair(self, request: RepairRequest) -> RepairResponse:
        allowed = {cap.capabilityId: cap for cap in request.capabilities}
        capability = allowed.get(request.capabilityId)
        if capability is None:
            return RepairResponse(
                capabilityId=request.capabilityId,
                args=request.args,
                needClarification=True,
                clarification="这个能力当前不可用。",
                reason="capability not allowed",
            )
        payload = {
            "capability": {
                "capabilityId": capability.capabilityId,
                "displayName": capability.displayName,
                "description": capability.description,
                "inputSchema": capability.inputSchema,
            },
            "originalArgs": request.args,
            "errorCode": request.errorCode,
            "errorMessage": request.errorMessage,
            "userMessage": request.userMessage,
            "optionCatalog": request.optionCatalog,
        }
        data, usage = self.client.chat_json(
            REPAIR_PROMPT,
            "请修正参数，只输出 JSON：\n" + json.dumps(payload, ensure_ascii=False, indent=2),
        )
        args = data.get("args")
        if not isinstance(args, dict):
            args = request.args
        return RepairResponse(
            capabilityId=_as_text(data.get("capabilityId")) or request.capabilityId,
            args=args,
            needClarification=bool(data.get("needClarification", False)),
            clarification=_as_text(data.get("clarification")),
            reason=_as_text(data.get("reason")),
            usage=usage,
        )


    def recover(self, request: RecoveryRequest) -> RecoveryResponse:
        """失败反馈 -> 自修复动作。LLM 只做决策，由 Java 侧执行或追问。"""
        allowed = {cap.capabilityId: cap for cap in request.capabilities}
        payload = {
            "userMessage": request.userMessage,
            "failedCapabilityId": request.capabilityId,
            "args": request.args,
            "errorCode": request.errorCode,
            "errorMessage": request.errorMessage,
            "errorCategory": request.errorCategory,
            "capabilities": [
                {
                    "capabilityId": cap.capabilityId,
                    "displayName": cap.displayName,
                    "description": cap.description,
                    "readOnly": cap.readOnly,
                    "requiresConfirmation": cap.requiresConfirmation,
                    "inputSchema": cap.inputSchema,
                }
                for cap in request.capabilities
            ],
            "optionCatalog": request.optionCatalog,
        }
        data, usage = self.client.chat_json(
            RECOVERY_PROMPT,
            "请基于以下失败反馈做自修复决策，只输出 JSON：\n"
            + json.dumps(payload, ensure_ascii=False, indent=2),
        )

        action = (_as_text(data.get("action")) or "NO_CAPABILITY").upper()
        if action not in {"CLARIFY", "RETRY", "ALTERNATIVE", "NO_CAPABILITY"}:
            action = "NO_CAPABILITY"

        capability_id = _as_text(data.get("capabilityId"))
        if capability_id is not None and capability_id not in allowed:
            capability_id = None
        # 只有 RETRY 可以回退到原能力；ALTERNATIVE 编造的能力必须丢弃，不能误执行。
        if action == "RETRY" and capability_id is None and request.capabilityId in allowed:
            capability_id = request.capabilityId

        args = data.get("args")
        if not isinstance(args, dict):
            args = {}

        missing_fields = data.get("missingFields")
        if not isinstance(missing_fields, list):
            missing_fields = []
        missing_fields = [str(item) for item in missing_fields if str(item).strip()]

        clarification = _as_text(data.get("clarification"))
        if missing_fields:
            action = "CLARIFY"
            if clarification is None:
                clarification = "还需要补充：" + "、".join(missing_fields)

        if action in {"RETRY", "ALTERNATIVE"} and capability_id is None:
            action = "NO_CAPABILITY"
            clarification = clarification or "现有能力里没有合适的处理方式。"

        if action == "CLARIFY" and clarification is None:
            clarification = "我还需要确认一些关键信息，能再补充一下吗？"
        if action == "NO_CAPABILITY" and clarification is None:
            clarification = "目前没有这个功能，我可以帮你查工单、建单、派单或审查合同。"

        return RecoveryResponse(
            action=action,
            capabilityId=capability_id,
            args=args,
            clarification=clarification,
            missingFields=missing_fields,
            reason=_as_text(data.get("reason")),
            usage=usage,
        )


def _parse_plan(raw_plan: Any, allowed: dict[str, CapabilityIn],
                capability_id: str | None, final_args: dict[str, Any]) -> list[PlanStep]:
    plan: list[PlanStep] = []
    if isinstance(raw_plan, list):
        for item in raw_plan[:3]:
            if not isinstance(item, dict):
                continue
            cid = _as_text(item.get("capabilityId"))
            if cid not in allowed:
                continue
            args = item.get("args")
            plan.append(PlanStep(capabilityId=cid, args=args if isinstance(args, dict) else {}))
    if capability_id is not None:
        if not plan or plan[-1].capabilityId != capability_id:
            plan.append(PlanStep(capabilityId=capability_id, args=final_args))
    return plan


def build_user_prompt(request: RouteRequest) -> str:
    capabilities = [
        {
            "capabilityId": cap.capabilityId,
            "displayName": cap.displayName,
            "description": cap.description,
            "readOnly": cap.readOnly,
            "requiresConfirmation": cap.requiresConfirmation,
            "inputSchema": cap.inputSchema,
        }
        for cap in request.capabilities
    ]
    turns = [
        {"role": turn.role, "content": turn.content}
        for turn in request.recentTurns[-6:]
    ]
    payload = {
        "currentDomain": request.currentDomain,
        "capabilities": capabilities,
        "recentTurns": turns,
        "userMessage": request.userMessage,
        "optionCatalog": request.optionCatalog,
        "pending": request.pending.model_dump() if request.pending else None,
        "sessionSummary": request.sessionSummary,
        "userProfile": request.userProfile,
    }
    return "请基于以下输入做路由决策，只输出 JSON：\n" + json.dumps(
        payload, ensure_ascii=False, indent=2
    )


def _as_text(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _clamp_float(value: Any, default: float) -> float:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return default
    return max(0.0, min(1.0, number))
