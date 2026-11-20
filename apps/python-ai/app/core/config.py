"""环境配置。所有密钥只从环境变量读取，不落代码/日志。"""

from __future__ import annotations

import os
from dataclasses import dataclass


def _env_int(name: str, default: int) -> int:
    try:
        return int(os.getenv(name, str(default)))
    except ValueError:
        return default


def _env_float(name: str, default: float) -> float:
    try:
        return float(os.getenv(name, str(default)))
    except ValueError:
        return default


@dataclass(frozen=True)
class Settings:
    internal_token: str = os.getenv("INTERNAL_TOKEN_J2P", "")
    deepseek_api_key: str = os.getenv("DEEPSEEK_API_KEY", "")
    deepseek_base_url: str = os.getenv("DEEPSEEK_BASE_URL", "https://api.deepseek.com")
    model: str = os.getenv("LLM_MODEL", "deepseek-chat")
    timeout_seconds: float = _env_float("LLM_TIMEOUT_SECONDS", 8.0)
    max_tokens: int = _env_int("LLM_MAX_TOKENS", 800)
    confidence_threshold: float = _env_float("LLM_CONFIDENCE_THRESHOLD", 0.55)

    @property
    def llm_configured(self) -> bool:
        return bool(self.deepseek_api_key.strip())


settings = Settings()
