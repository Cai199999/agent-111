"""DeepSeek / OpenAI 兼容客户端。

只负责一次 JSON 决策调用；不执行工具、不访问子 Agent、不持有业务数据。
"""

from __future__ import annotations

import json
import logging
import re
import time
from typing import Any

from openai import OpenAI

from app.core.config import Settings

logger = logging.getLogger(__name__)


class LlmUnavailable(RuntimeError):
    """LLM 未配置、超时或返回不可用。Java 侧会回退规则路由。"""


class DeepSeekClient:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self._client: OpenAI | None = None

    def _get_client(self) -> OpenAI:
        if not self.settings.llm_configured:
            raise LlmUnavailable("DEEPSEEK_API_KEY 未配置")
        if self._client is None:
            self._client = OpenAI(
                api_key=self.settings.deepseek_api_key,
                base_url=self.settings.deepseek_base_url,
                timeout=self.settings.timeout_seconds,
                max_retries=0,
            )
        return self._client

    def chat_json(self, system_prompt: str, user_prompt: str) -> tuple[dict[str, Any], dict[str, Any]]:
        client = self._get_client()
        last_error: Exception | None = None
        for attempt in range(2):
            try:
                response = self._create(client, system_prompt, user_prompt)
                content = (response.choices[0].message.content or "").strip()
                data = _parse_json(content)
                usage = _usage(response)
                return data, usage
            except Exception as exc:  # noqa: BLE001 - 统一转成 LlmUnavailable
                last_error = exc
                logger.warning("LLM call failed attempt=%s error=%s", attempt + 1, exc)
                if attempt == 0:
                    time.sleep(0.4)
        raise LlmUnavailable(f"LLM 调用失败：{last_error}") from last_error

    def _create(self, client: OpenAI, system_prompt: str, user_prompt: str):
        try:
            return client.chat.completions.create(
                model=self.settings.model,
                messages=[
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": user_prompt},
                ],
                temperature=0,
                max_tokens=self.settings.max_tokens,
                response_format={"type": "json_object"},
            )
        except Exception as exc:  # noqa: BLE001
            # 部分兼容端点不支持 response_format；去掉后重试一次
            if "response_format" not in str(exc):
                raise
            return client.chat.completions.create(
                model=self.settings.model,
                messages=[
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": user_prompt},
                ],
                temperature=0,
                max_tokens=self.settings.max_tokens,
            )


def _parse_json(content: str) -> dict[str, Any]:
    if not content:
        raise ValueError("LLM 返回空内容")
    try:
        data = json.loads(content)
    except json.JSONDecodeError:
        match = re.search(r"\{.*\}", content, re.DOTALL)
        if not match:
            raise ValueError(f"LLM 未返回 JSON：{content[:200]}") from None
        data = json.loads(match.group(0))
    if not isinstance(data, dict):
        raise ValueError("LLM 返回的 JSON 不是对象")
    return data


def _usage(response: Any) -> dict[str, Any]:
    usage = getattr(response, "usage", None)
    if usage is None:
        return {}
    return {
        "promptTokens": getattr(usage, "prompt_tokens", 0),
        "completionTokens": getattr(usage, "completion_tokens", 0),
        "totalTokens": getattr(usage, "total_tokens", 0),
    }
