"""把子 Agent 原始 output 解释成简短中文，不修改数据本身。"""

from __future__ import annotations

import json

from app.llm.client import DeepSeekClient
from app.schemas import ExplainRequest, ExplainResponse

EXPLAIN_PROMPT = """你是"物业主 Agent"的结果解释器。
用户会给你 capabilityId、用户问题和子 Agent 的原始 output。
请用中文简洁说明结果，最多 120 字，只输出一个 JSON 对象：
{"explanation": "..."}

规则：
1) 只依据 output 解释，不要编造成员、数量、状态或时间。
2) 列表结果说明条数和最关键的字段/状态；不要逐条复述全部数据。
3) 不要输出 JSON 数据本身，不要输出代码或工具指令。
4) 如果 output 为空，说明“未返回数据”。
5) 解释是给人看的摘要，原始 output 会另外原样展示，不要声称自己修改了数据。
6) 所有枚举必须翻译成中文，不要输出英文 code；例如“待处理”“普通”“待接单”。
   原始 output 会另行保留，不需要在解释里复述 code。
7) 参考 codeLabels 做翻译；codeLabels 没有的 code 也要尽量用中文描述。"""

# 状态/节点是子 Agent 的稳定枚举；ticketTypes/priorities 优先使用 optionCatalog
STATUS_LABELS = {
    "PENDING": "待处理",
    "ASSIGNED": "已派单",
    "ACCEPTED": "已接单",
    "PROCESSING": "处理中",
    "RUNNING": "处理中",
    "RESOLVED": "已解决",
    "VERIFIED": "已验证",
    "PENDING_VERIFY": "待验证",
    "CLOSED": "已关闭",
    "CANCELLED": "已取消",
    "HOLD": "已挂起",
}
CURRENT_NODE_LABELS = {
    "ACCEPT": "待接单",
    "ACCEPTED": "已接单",
    "PROCESS": "处理中",
    "PROCESSING": "处理中",
    "VERIFY": "待验证",
    "CLOSE": "已关闭",
    "CLOSED": "已关闭",
}
PRIORITY_LABELS = {
    "LOW": "低",
    "NORMAL": "普通",
    "HIGH": "高",
    "URGENT": "紧急",
}


class ExplainService:
    def __init__(self, client: DeepSeekClient) -> None:
        self.client = client

    def explain(self, request: ExplainRequest) -> ExplainResponse:
        catalog = request.optionCatalog or {}
        payload = {
            "capabilityId": request.capabilityId,
            "userMessage": request.userMessage,
            "output": request.output,
            "optionCatalog": catalog,
            "codeLabels": _build_labels(catalog),
        }
        try:
            data, usage = self.client.chat_json(
                EXPLAIN_PROMPT,
                "请解释以下结果，只输出 JSON：\n" + json.dumps(payload, ensure_ascii=False),
            )
            explanation = data.get("explanation")
            if isinstance(explanation, str) and explanation.strip():
                return ExplainResponse(explanation=explanation.strip(), usage=usage)
        except Exception:
            pass
        return ExplainResponse(explanation=_fallback(request.output), usage={})


def _build_labels(catalog: dict) -> dict:
    priority = dict(PRIORITY_LABELS)
    for item in catalog.get("priorities", []) or []:
        code = item.get("code")
        name = item.get("name")
        if code and name:
            priority[str(code)] = str(name)
    ticket_type = {}
    for item in catalog.get("ticketTypes", []) or []:
        code = item.get("code")
        name = item.get("name")
        if code and name:
            ticket_type[str(code)] = str(name)
    return {
        "status": STATUS_LABELS,
        "currentNode": CURRENT_NODE_LABELS,
        "priority": priority,
        "ticketType": ticket_type,
    }


def _fallback(output: object) -> str:
    if output is None:
        return "子 Agent 未返回数据。"
    if isinstance(output, list):
        return f"子 Agent 返回 {len(output)} 条记录，原始数据见下方。"
    if isinstance(output, dict):
        return f"子 Agent 返回 {len(output)} 个字段，原始数据见下方。"
    return "子 Agent 已返回结果，原始数据见下方。"
