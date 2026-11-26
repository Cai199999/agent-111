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
    inputSchema: dict[str, Any] | None = None


class TurnIn(BaseModel):
    role: str
    content: str


class PendingContext(BaseModel):
    capabilityId: str | None = None
    args: dict[str, Any] = Field(default_factory=dict)
    missingFields: list[str] = Field(default_factory=list)


class RouteRequest(BaseModel):
    traceId: str | None = None
    sessionNo: str | None = None
    userMessage: str = Field(min_length=1, max_length=4000)
    recentTurns: list[TurnIn] = Field(default_factory=list)
    capabilities: list[CapabilityIn] = Field(default_factory=list)
    currentDomain: str | None = None
    optionCatalog: dict[str, Any] | None = None
    pending: PendingContext | None = None
    sessionSummary: str | None = None
    userProfile: str | None = None


class PlanStep(BaseModel):
    capabilityId: str
    args: dict[str, Any] = Field(default_factory=dict)


class RouteResponse(BaseModel):
    intent: str | None = None
    capabilityId: str | None = None
    args: dict[str, Any] = Field(default_factory=dict)
    confidence: float = 0.0
    needClarification: bool = False
    clarification: str | None = None
    reason: str | None = None
    plan: list[PlanStep] = Field(default_factory=list)
    missingFields: list[str] = Field(default_factory=list)
    usage: dict[str, Any] = Field(default_factory=dict)


class RepairRequest(BaseModel):
    userMessage: str
    capabilityId: str
    args: dict[str, Any] = Field(default_factory=dict)
    errorCode: str | None = None
    errorMessage: str | None = None
    capabilities: list[CapabilityIn] = Field(default_factory=list)
    optionCatalog: dict[str, Any] | None = None


class ExplainRequest(BaseModel):
    userMessage: str
    capabilityId: str
    output: Any = None
    optionCatalog: dict[str, Any] | None = None


class ExplainResponse(BaseModel):
    explanation: str
    usage: dict[str, Any] = Field(default_factory=dict)


class UserProfileRequest(BaseModel):
    userId: str | int | None = None
    currentProfile: dict[str, Any] | None = None
    sessionSummary: str | None = None
    messages: list[TurnIn] = Field(default_factory=list)
    optionCatalog: dict[str, Any] | None = None


class UserProfileResponse(BaseModel):
    profile: dict[str, Any] = Field(default_factory=dict)
    changed: bool = False
    confidence: float = 0.0
    reason: str | None = None
    usage: dict[str, Any] = Field(default_factory=dict)


class SummarizeRequest(BaseModel):
    sessionNo: str | None = None
    previousSummary: str | None = None
    messages: list[TurnIn] = Field(default_factory=list)


class SummarizeResponse(BaseModel):
    summary: str
    usage: dict[str, Any] = Field(default_factory=dict)


class RepairResponse(BaseModel):
    capabilityId: str
    args: dict[str, Any] = Field(default_factory=dict)
    needClarification: bool = False
    clarification: str | None = None
    reason: str | None = None
    usage: dict[str, Any] = Field(default_factory=dict)


class RecoveryRequest(BaseModel):
    """A2A 调用失败后的自修复请求：由主 Agent 把失败反馈回传。"""

    userMessage: str = Field(min_length=1, max_length=4000)
    capabilityId: str | None = None
    args: dict[str, Any] = Field(default_factory=dict)
    errorCode: str | None = None
    errorMessage: str | None = None
    errorCategory: str | None = None
    capabilities: list[CapabilityIn] = Field(default_factory=list)
    optionCatalog: dict[str, Any] | None = None


class RecoveryResponse(BaseModel):
    """自修复动作：CLARIFY 问用户，RETRY 修正后重试，ALTERNATIVE 换能力，NO_CAPABILITY 兜底。"""

    action: str = "NO_CAPABILITY"
    capabilityId: str | None = None
    args: dict[str, Any] = Field(default_factory=dict)
    clarification: str | None = None
    missingFields: list[str] = Field(default_factory=list)
    reason: str | None = None
    usage: dict[str, Any] = Field(default_factory=dict)


# ---------------------------------------------------------------------------
# 多 Agent 编排骨架（第一阶段）
# Python 只做路由/计划/验收；真正调用子 Agent 的仍是 Java BFF。
# ---------------------------------------------------------------------------


class OrchestrationCapability(BaseModel):
    capabilityId: str
    agentKey: str | None = None
    displayName: str | None = None
    description: str | None = None
    readOnly: bool = False
    requiresConfirmation: bool = False


class OrchestrationRequest(BaseModel):
    traceId: str | None = None
    sessionNo: str | None = None
    userMessage: str = Field(min_length=1, max_length=4000)
    actorId: str | None = None
    tenantId: str | None = None
    capabilities: list[OrchestrationCapability] = Field(default_factory=list)
    # Java/上层已确定的计划；非空时优先于 Python 路由
    plan: list[PlanStep] = Field(default_factory=list)
    # PLAN_ONLY：只产出派发计划，由 Java 执行；MOCK：进程内 mock 跑通闭环
    dispatchMode: str = "PLAN_ONLY"
    maxRounds: int = 2


class OrchestrationStepResult(BaseModel):
    capabilityId: str
    agentKey: str | None = None
    status: str = "PENDING"
    output: Any = None
    error: str | None = None
    missingFields: list[str] = Field(default_factory=list)


class OrchestrationResponse(BaseModel):
    verdict: str = "FAILED"
    capabilityId: str | None = None
    args: dict[str, Any] = Field(default_factory=dict)
    plan: list[PlanStep] = Field(default_factory=list)
    steps: list[OrchestrationStepResult] = Field(default_factory=list)
    clarification: str | None = None
    notes: list[str] = Field(default_factory=list)
    rounds: int = 0
    usage: dict[str, Any] = Field(default_factory=dict)


class OrchestrationVerifyRequest(BaseModel):
    """Java 执行完编排计划后回传结果，由 Python 只做结构化验收。"""

    traceId: str | None = None
    sessionNo: str | None = None
    plan: list[PlanStep] = Field(default_factory=list)
    steps: list[OrchestrationStepResult] = Field(default_factory=list)


class OrchestrationVerifyResponse(BaseModel):
    verdict: str = "FAILED"
    clarification: str | None = None
    notes: list[str] = Field(default_factory=list)
