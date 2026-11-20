"""用户画像：只提取稳定、明确的偏好/事实，全局使用但不跨会话读取原文。"""

from __future__ import annotations

import json

from app.llm.client import DeepSeekClient
from app.schemas import UserProfileRequest, UserProfileResponse

PROFILE_PROMPT = """你是"物业主 Agent"的用户画像提取器。
请根据 currentProfile、当前会话摘要和最近消息，提取稳定的用户偏好/事实。
只输出 JSON：
{"profile": {...}, "changed": true/false, "confidence": 0.0-1.0, "reason": "..."}

只允许以下字段：
- preferredProjectId: integer
- preferredBuildingId: integer
- preferredRoomId: integer
- preferredTicketTypeCode: string（必须来自 optionCatalog.ticketTypes）
- preferredPriorityCode: string（必须来自 optionCatalog.priorities）
- commonIntents: string[]（如 ticket.create / ticket.query / contract.review）
- notes: string（不超过 80 字）

规则：
1) 只提取用户明确表达、可复用的偏好；不要推断敏感信息（姓名、电话、身份证、详细住址）。
2) 没有明确证据时保持 currentProfile 不变，changed=false。
3) 不读取其他会话原文；只用当前会话摘要和最近消息。
4) 不编造 ID/枚举；枚举必须来自 optionCatalog。
5) 当前消息明确改变偏好时以当前消息为准。"""

ALLOWED_KEYS = {
    "preferredProjectId",
    "preferredBuildingId",
    "preferredRoomId",
    "preferredTicketTypeCode",
    "preferredPriorityCode",
    "commonIntents",
    "notes",
}


class ProfileService:
    def __init__(self, client: DeepSeekClient) -> None:
        self.client = client

    def extract(self, request: UserProfileRequest) -> UserProfileResponse:
        current = request.currentProfile or {}
        payload = {
            "currentProfile": current,
            "sessionSummary": request.sessionSummary,
            "messages": [message.model_dump() for message in request.messages[-8:]],
            "optionCatalog": request.optionCatalog,
        }
        try:
            data, usage = self.client.chat_json(
                PROFILE_PROMPT,
                "请提取用户画像，只输出 JSON：\n" + json.dumps(payload, ensure_ascii=False),
            )
            profile = data.get("profile")
            if not isinstance(profile, dict):
                profile = current
            profile = {key: value for key, value in profile.items() if key in ALLOWED_KEYS}
            confidence = _clamp(data.get("confidence"))
            changed = bool(data.get("changed", False)) and confidence >= 0.6
            if not changed:
                profile = current
            return UserProfileResponse(
                profile=profile,
                changed=changed,
                confidence=confidence,
                reason=data.get("reason") if isinstance(data.get("reason"), str) else None,
                usage=usage,
            )
        except Exception:
            return UserProfileResponse(profile=current, changed=False, confidence=0.0)


def _clamp(value: object) -> float:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return 0.0
    return max(0.0, min(1.0, number))
