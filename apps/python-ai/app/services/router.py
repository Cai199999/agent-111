"""路由决策：LLM 只输出 capabilityId/args/confidence，不执行任何工具。"""

from __future__ import annotations

import json
from typing import Any

from app.core.config import Settings
from app.llm.client import DeepSeekClient
from app.schemas import CapabilityIn, RouteRequest, RouteResponse

SYSTEM_PROMPT = """你是"物业主 Agent"的路由决策器。
你只能从给定的 capabilities 中选择 capabilityId，不能编造能力。
你必须只输出一个 JSON 对象，不要输出 Markdown、解释或多余文字。

JSON 字段：
- capabilityId: string|null，必须来自 capabilities；无法判断时为 null
- intent: string|null，简短意图名
- args: object，只填用户明确提供的信息，不要猜测
- confidence: number，0 到 1
- needClarification: boolean，是否必须先追问用户
- clarification: string|null，追问内容，中文、简短
- reason: string|null，路由原因

规则：
1) 用户问题跨域（例如只说"知识库"）时，capabilityId=null，needClarification=true，追问是物业还是合同。
2) 写操作（readOnly=false 或 requiresConfirmation=true）仍然只做路由，最终由主 Agent 让用户确认。
3) 不要输出工具调用、代码、SQL 或任何执行指令。
4) 不确定时宁可追问，不要猜错域。
5) args 只能包含能力所需字段；缺少关键字段时 needClarification=true。"""


class RouteService:
    def __init__(self, client: DeepSeekClient, settings: Settings) -> None:
        self.client = client
        self.settings = settings

    def route(self, request: RouteRequest) -> RouteResponse:
        allowed = {cap.capabilityId: cap for cap in request.capabilities}
        system_prompt = SYSTEM_PROMPT
        user_prompt = build_user_prompt(request)
        data, usage = self.client.chat_json(system_prompt, user_prompt)

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

        return RouteResponse(
            intent=_as_text(data.get("intent")),
            capabilityId=capability_id,
            args=args,
            confidence=confidence,
            needClarification=need_clarification,
            clarification=clarification,
            reason=_as_text(data.get("reason")),
            usage=usage,
        )


def build_user_prompt(request: RouteRequest) -> str:
    capabilities = [
        {
            "capabilityId": cap.capabilityId,
            "displayName": cap.displayName,
            "description": cap.description,
            "readOnly": cap.readOnly,
            "requiresConfirmation": cap.requiresConfirmation,
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
