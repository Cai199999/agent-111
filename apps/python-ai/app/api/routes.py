"""内部 HTTP API。仅 Java BFF 调用，使用内部令牌。"""

from __future__ import annotations

import logging

from fastapi import APIRouter, Depends, Header, HTTPException

from app.core.config import settings
from app.llm.client import DeepSeekClient, LlmUnavailable
from app.schemas import RouteRequest, RouteResponse
from app.services.router import RouteService

logger = logging.getLogger(__name__)

router = APIRouter()
service = RouteService(DeepSeekClient(settings), settings)


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
