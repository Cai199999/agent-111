"""路由决策：LLM 只输出 capabilityId/args/confidence，不执行任何工具。"""

from __future__ import annotations

import json
from typing import Any

from app.core.config import Settings
from app.llm.client import DeepSeekClient
from app.schemas import CapabilityIn, PlanStep, RepairRequest, RepairResponse, RouteRequest, RouteResponse

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
   但不要拆出无意义的步骤。"""

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
