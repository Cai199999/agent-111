"""路由服务的请求/响应模型。字段与 Java 侧保持一致。"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field


class CapabilityIn(BaseModel):
    capabilityId: str
    displayName: str | None = None
    description: str | None = None
    readOnly: bool = False
    requiresConfirmation: bool = False


class TurnIn(BaseModel):
    role: str
    content: str


class RouteRequest(BaseModel):
    traceId: str | None = None
    sessionNo: str | None = None
    userMessage: str = Field(min_length=1, max_length=4000)
    recentTurns: list[TurnIn] = Field(default_factory=list)
    capabilities: list[CapabilityIn] = Field(default_factory=list)
    currentDomain: str | None = None


class RouteResponse(BaseModel):
    intent: str | None = None
    capabilityId: str | None = None
    args: dict[str, Any] = Field(default_factory=dict)
    confidence: float = 0.0
    needClarification: bool = False
    clarification: str | None = None
    reason: str | None = None
    usage: dict[str, Any] = Field(default_factory=dict)
