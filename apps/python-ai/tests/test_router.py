from __future__ import annotations

from fastapi.testclient import TestClient

from app.api import routes
from app.core.config import Settings
from app.schemas import CapabilityIn, RouteRequest
from app.services.router import RouteService


class FakeClient:
    def __init__(self, data: dict) -> None:
        self.data = data
        self.calls: list[tuple[str, str]] = []

    def chat_json(self, system_prompt: str, user_prompt: str):
        self.calls.append((system_prompt, user_prompt))
        return self.data, {"totalTokens": 42}


def capability(capability_id: str, read_only: bool = True) -> CapabilityIn:
    return CapabilityIn(
        capabilityId=capability_id,
        displayName=capability_id,
        description=capability_id,
        readOnly=read_only,
        requiresConfirmation=not read_only,
    )


def request(message: str) -> RouteRequest:
    return RouteRequest(
        traceId="trc-test",
        sessionNo="ws-test",
        userMessage=message,
        capabilities=[
            capability("property.ticket.query"),
            capability("contract.rule.list"),
        ],
    )


def test_valid_capability_is_returned():
    client = FakeClient(
        {
            "capabilityId": "property.ticket.query",
            "intent": "ticket.query",
            "args": {"status": "PENDING"},
            "confidence": 0.93,
            "needClarification": False,
            "reason": "用户问待处理工单",
        }
    )
    service = RouteService(client, Settings(deepseek_api_key="fake", confidence_threshold=0.55))

    response = service.route(request("我有哪些待处理工单？"))

    assert response.capabilityId == "property.ticket.query"
    assert response.confidence == 0.93
    assert response.args == {"status": "PENDING"}
    assert response.usage["totalTokens"] == 42
    assert "property.ticket.query" in client.calls[0][1]
    assert "我有哪些待处理工单" in client.calls[0][1]


def test_unknown_capability_requires_clarification():
    client = FakeClient(
        {
            "capabilityId": "property.ticket.delete_all",
            "confidence": 0.99,
            "needClarification": False,
        }
    )
    service = RouteService(client, Settings(deepseek_api_key="fake"))

    response = service.route(request("删掉所有工单"))

    assert response.capabilityId is None
    assert response.needClarification is True
    assert response.clarification


def test_low_confidence_requires_clarification():
    client = FakeClient(
        {
            "capabilityId": "contract.rule.list",
            "confidence": 0.2,
            "needClarification": False,
        }
    )
    service = RouteService(client, Settings(deepseek_api_key="fake", confidence_threshold=0.55))

    response = service.route(request("查点东西"))

    assert response.needClarification is True
    assert response.clarification


def test_api_rejects_wrong_internal_token(monkeypatch):
    from app.main import app

    monkeypatch.setattr(
        routes,
        "settings",
        Settings(internal_token="secret", deepseek_api_key="fake"),
    )
    monkeypatch.setattr(
        routes,
        "service",
        RouteService(
            FakeClient({"capabilityId": "property.ticket.query", "confidence": 0.9}),
            Settings(internal_token="secret", deepseek_api_key="fake"),
        ),
    )

    client = TestClient(app)
    unauthorized = client.post(
        "/internal/v1/ai/route",
        json=request("查工单").model_dump(),
        headers={"X-Internal-Token": "wrong"},
    )
    assert unauthorized.status_code == 401

    authorized = client.post(
        "/internal/v1/ai/route",
        json=request("查工单").model_dump(),
        headers={"X-Internal-Token": "secret"},
    )
    assert authorized.status_code == 200
    assert authorized.json()["capabilityId"] == "property.ticket.query"


def test_ready_reports_unconfigured_llm(monkeypatch):
    from app.main import app

    monkeypatch.setattr(routes, "settings", Settings(deepseek_api_key=""))
    client = TestClient(app)
    response = client.get("/ready")
    assert response.status_code == 503
