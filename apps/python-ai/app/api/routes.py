"""内部 HTTP API。仅 Java BFF 调用，使用内部令牌。"""

from __future__ import annotations

import logging

from fastapi import APIRouter, Depends, Header, HTTPException

from app.core.config import settings
from app.llm.client import DeepSeekClient, LlmUnavailable
from app.orchestrator import MockDispatcher, Orchestrator, PlanOnlyDispatcher
from app.schemas import (
    ExplainRequest,
    ExplainResponse,
    OrchestrationRequest,
    OrchestrationResponse,
    RecoveryRequest,
    RecoveryResponse,
    RepairRequest,
    RepairResponse,
    RouteRequest,
    RouteResponse,
    SummarizeRequest,
    SummarizeResponse,
    UserProfileRequest,
    UserProfileResponse,
)
from app.services.explain import ExplainService
from app.services.profile import ProfileService
from app.services.summarize import SummarizeService
from app.services.router import RouteService

logger = logging.getLogger(__name__)

router = APIRouter()
_client = DeepSeekClient(settings)
service = RouteService(_client, settings)
explain_service = ExplainService(_client)
summarize_service = SummarizeService(_client)
profile_service = ProfileService(_client)

# 编排骨架：PLAN_ONLY 只产出派发计划（Java 执行），MOCK 仅用于演示/单测。
plan_only_orchestrator = Orchestrator(PlanOnlyDispatcher())
mock_orchestrator = Orchestrator(MockDispatcher())


def verify_internal_token(x_internal_token: str = Header(default="")) -> None:
    if settings.internal_token and x_internal_token != settings.internal_token:
        raise HTTPException(status_code=401, detail="INVALID_INTERNAL_TOKEN")


@router.get("/health")
def health() -> dict:
    return {
        "status": "UP",
        "service": "wuye-agent-python-ai",
        "model": settings.model,
        "llmConfigured": settings.llm_configured,
    }


@router.get("/ready")
def ready() -> dict:
    if not settings.llm_configured:
        raise HTTPException(status_code=503, detail="LLM_NOT_CONFIGURED")
    return {"status": "UP", "checks": {"llm": "UP", "model": settings.model}}


@router.post(
    "/internal/v1/ai/route",
    response_model=RouteResponse,
    dependencies=[Depends(verify_internal_token)],
)
def route(request: RouteRequest) -> RouteResponse:
    try:
        response = service.route(request)
        logger.info(
            "route traceId=%s capability=%s confidence=%s clarify=%s usage=%s",
            request.traceId,
            response.capabilityId,
            response.confidence,
            response.needClarification,
            response.usage,
        )
        return response
    except LlmUnavailable as exc:
        logger.warning("llm unavailable traceId=%s error=%s", request.traceId, exc)
        raise HTTPException(status_code=503, detail=str(exc)) from exc


@router.post(
    "/internal/v1/ai/recover",
    response_model=RecoveryResponse,
    dependencies=[Depends(verify_internal_token)],
)
def recover(request: RecoveryRequest) -> RecoveryResponse:
    try:
        response = service.recover(request)
        logger.info(
            "recover capability=%s action=%s clarify=%s usage=%s",
            request.capabilityId,
            response.action,
            response.action == "CLARIFY",
            response.usage,
        )
        return response
    except LlmUnavailable as exc:
        logger.warning("llm recover unavailable error=%s", exc)
        raise HTTPException(status_code=503, detail=str(exc)) from exc


@router.post(
    "/internal/v1/ai/explain",
    response_model=ExplainResponse,
    dependencies=[Depends(verify_internal_token)],
)
def explain(request: ExplainRequest) -> ExplainResponse:
    response = explain_service.explain(request)
    logger.info(
        "explain capability=%s explanation=%s usage=%s",
        request.capabilityId,
        response.explanation[:80],
        response.usage,
    )
    return response


@router.post(
    "/internal/v1/ai/summarize",
    response_model=SummarizeResponse,
    dependencies=[Depends(verify_internal_token)],
)
def summarize(request: SummarizeRequest) -> SummarizeResponse:
    response = summarize_service.summarize(request)
    logger.info("summarize session=%s usage=%s", request.sessionNo, response.usage)
    return response


@router.post(
    "/internal/v1/ai/profile",
    response_model=UserProfileResponse,
    dependencies=[Depends(verify_internal_token)],
)
def profile(request: UserProfileRequest) -> UserProfileResponse:
    response = profile_service.extract(request)
    logger.info("profile user=%s changed=%s confidence=%s usage=%s",
                request.userId, response.changed, response.confidence, response.usage)
    return response


@router.post(
    "/internal/v1/ai/repair",
    response_model=RepairResponse,
    dependencies=[Depends(verify_internal_token)],
)
def repair(request: RepairRequest) -> RepairResponse:
    try:
        response = service.repair(request)
        logger.info(
            "repair capability=%s clarify=%s usage=%s",
            response.capabilityId,
            response.needClarification,
            response.usage,
        )
        return response
    except LlmUnavailable as exc:
        logger.warning("llm repair unavailable error=%s", exc)
        raise HTTPException(status_code=503, detail=str(exc)) from exc


@router.post(
    "/internal/v1/ai/orchestrate",
    response_model=OrchestrationResponse,
    dependencies=[Depends(verify_internal_token)],
)
def orchestrate(request: OrchestrationRequest) -> OrchestrationResponse:
    """多 Agent 编排骨架：route → plan → dispatch → collect → verify。

    PLAN_ONLY 返回派发计划，由 Java BFF 真正调用子 Agent；
    MOCK 只在本地演示与单测中使用，绝不代表生产能力。
    """
    mode = (request.dispatchMode or "PLAN_ONLY").upper()
    if mode == "MOCK":
        orchestrator = mock_orchestrator
    elif mode == "PLAN_ONLY":
        orchestrator = plan_only_orchestrator
    else:
        raise HTTPException(status_code=400, detail="UNSUPPORTED_DISPATCH_MODE")

    response = orchestrator.run(request)
    logger.info(
        "orchestrate traceId=%s mode=%s verdict=%s capability=%s steps=%s rounds=%s",
        request.traceId,
        mode,
        response.verdict,
        response.capabilityId,
        len(response.steps),
        response.rounds,
    )
    return response
