from __future__ import annotations

from fastapi.testclient import TestClient

from app.api import routes
from app.core.config import Settings
from app.schemas import CapabilityIn, ExplainRequest, PendingContext, RepairRequest, RouteRequest
from app.services.explain import ExplainService
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


def test_plan_and_missing_fields_are_parsed():
    client = FakeClient(
        {
            "capabilityId": "property.ticket.create",
            "confidence": 0.9,
            "needClarification": False,
            "plan": [
                {"capabilityId": "property.profile.get", "args": {}},
                {
                    "capabilityId": "property.ticket.create",
                    "args": {"description": "水管漏水", "locationText": "3栋2单元"},
                },
            ],
            "missingFields": ["ticketTypeCode"],
        }
    )
    service = RouteService(client, Settings(deepseek_api_key="fake"))
    create_request = RouteRequest(
        traceId="trc-test",
        sessionNo="ws-test",
        userMessage="创建工单：3栋2单元水管漏水",
        capabilities=[
            capability("property.profile.get"),
            capability("property.ticket.create", read_only=False),
        ],
    )

    response = service.route(create_request)

    assert [step.capabilityId for step in response.plan] == [
        "property.profile.get",
        "property.ticket.create",
    ]
    assert response.missingFields == ["ticketTypeCode"]
    assert response.needClarification is True
    assert "ticketTypeCode" in response.clarification


def test_pending_context_is_sent_to_llm():
    client = FakeClient(
        {
            "capabilityId": "property.ticket.create",
            "args": {"description": "水管漏水", "ticketTypeCode": "PUMP"},
            "confidence": 0.9,
            "needClarification": False,
        }
    )
    service = RouteService(client, Settings(deepseek_api_key="fake"))
    request_data = RouteRequest(
        traceId="trc-test",
        sessionNo="ws-test",
        userMessage="工单类型选水泵故障",
        capabilities=[capability("property.ticket.create", read_only=False)],
        pending=PendingContext(
            capabilityId="property.ticket.create",
            args={"description": "水管漏水"},
            missingFields=["ticketTypeCode"],
        ),
    )

    response = service.route(request_data)

    assert response.capabilityId == "property.ticket.create"
    assert "pending" in client.calls[0][1]
    assert "ticketTypeCode" in client.calls[0][1]


def test_explain_returns_natural_language():
    client = FakeClient({"explanation": "子 Agent 返回 2 条工单，均为待处理。"})
    service = ExplainService(client)

    response = service.explain(
        ExplainRequest(
            userMessage="我有哪些工单？",
            capabilityId="property.ticket.query",
            output=[{"ticketNo": "GD1", "statusCode": "PENDING"}],
            optionCatalog={"priorities": [{"code": "NORMAL", "name": "普通"}]},
        )
    )

    assert response.explanation == "子 Agent 返回 2 条工单，均为待处理。"
    prompt = client.calls[0][1]
    assert "PENDING" in prompt and "待处理" in prompt
    assert "普通" in prompt


def test_repair_returns_corrected_args():
    client = FakeClient(
        {
            "capabilityId": "property.ticket.create",
            "args": {"ticketTypeCode": "PLUMBING", "description": "水管漏水"},
            "needClarification": False,
            "reason": "补充必填字段",
        }
    )
    service = RouteService(client, Settings(deepseek_api_key="fake"))
    request_data = RepairRequest(
        userMessage="创建工单：3栋2单元水管漏水",
        capabilityId="property.ticket.create",
        args={"description": "水管漏水"},
        errorCode="60001",
        errorMessage="ticketTypeCode不能为空",
        capabilities=[capability("property.ticket.create", read_only=False)],
    )

    response = service.repair(request_data)

    assert response.args["ticketTypeCode"] == "PLUMBING"
    assert response.needClarification is False
    assert "ticketTypeCode" in client.calls[0][1]


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
