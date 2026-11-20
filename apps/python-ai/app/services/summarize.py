"""会话级记忆压缩：只针对当前会话，不做跨会话检索。"""

from __future__ import annotations

import json

from app.llm.client import DeepSeekClient
from app.schemas import SummarizeRequest, SummarizeResponse

SUMMARIZE_PROMPT = """你是"物业主 Agent"的会话记忆压缩器。
请把当前会话压缩成一段中文摘要，最多 300 字，只输出 JSON：
{"summary": "..."}

必须保留：
- 用户目标与已完成的动作；
- 已确认的事实、ID、工单号、合同号、位置、类型、优先级；
- 未完成的待办/追问；
- 用户明确表达的偏好。

规则：
1) 只总结当前会话，不要引入其他会话或知识库内容。
2) 不要编造、不要改写 ID/枚举/时间。
3) 不要输出原文大段复述，不要输出代码或工具指令。
4) 已有 previousSummary 时，在其基础上增量更新，不要丢失关键事实。"""


class SummarizeService:
    def __init__(self, client: DeepSeekClient) -> None:
        self.client = client

    def summarize(self, request: SummarizeRequest) -> SummarizeResponse:
        payload = {
            "sessionNo": request.sessionNo,
            "previousSummary": request.previousSummary,
            "messages": [message.model_dump() for message in request.messages],
        }
        try:
            data, usage = self.client.chat_json(
                SUMMARIZE_PROMPT,
                "请压缩以下会话，只输出 JSON：\n" + json.dumps(payload, ensure_ascii=False),
            )
            summary = data.get("summary")
            if isinstance(summary, str) and summary.strip():
                return SummarizeResponse(summary=summary.strip(), usage=usage)
        except Exception:
            pass
        return SummarizeResponse(summary=request.previousSummary or "", usage={})
