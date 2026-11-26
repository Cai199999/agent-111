from __future__ import annotations

from fastapi.testclient import TestClient

from app.api import routes
from app.core.config import Settings
from app.schemas import (
    CapabilityIn,
    ExplainRequest,
    PendingContext,
    RecoveryRequest,
    RepairRequest,
    RouteRequest,
    SkillIn,
    SummarizeRequest,
    UserProfileRequest,
)
from app.services.explain import ExplainService
from app.services.profile import ProfileService
from app.services.router import RouteService
from app.services.summarize import SummarizeService


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


def test_skill_is_validated_and_included_in_prompt():
    client = FakeClient(
        {
            "capabilityId": "property.ticket.query",
            "skillId": "property.ticket.query",
            "confidence": 0.9,
        }
    )
    service = RouteService(
        client, Settings(deepseek_api_key="fake", confidence_threshold=0.55)
    )
    request_with_skill = RouteRequest(
        userMessage="查工单",
        capabilities=[capability("property.ticket.query")],
        skills=[
            SkillIn(
                skillId="property.ticket.query",
                capabilities=["property.ticket.query"],
                taskTypes=["ticket.query"],
            )
        ],
    )

    response = service.route(request_with_skill)

    assert response.capabilityId == "property.ticket.query"
    assert response.skillId == "property.ticket.query"
    assert '"skillId"' in client.calls[0][1]
    assert '"skills"' in client.calls[0][1]


def test_skill_not_covering_capability_falls_back():
    client = FakeClient(
        {
            "capabilityId": "property.ticket.query",
            "skillId": "contract.review",
            "confidence": 0.9,
        }
    )
    service = RouteService(
        client, Settings(deepseek_api_key="fake", confidence_threshold=0.55)
    )
    request_with_skill = RouteRequest(
        userMessage="查工单",
        capabilities=[capability("property.ticket.query")],
        skills=[
            SkillIn(
                skillId="contract.review",
                capabilities=["contract.review"],
            )
        ],
    )

    response = service.route(request_with_skill)

    assert response.capabilityId == "property.ticket.query"
    assert response.skillId is None


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
    service = RouteService(
        client, Settings(deepseek_api_key="fake", confidence_threshold=0.55)
    )

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


def test_summarize_keeps_current_session_only():
    client = FakeClient({"summary": "用户在建单，已确认 PUMP 类型，等待楼栋。"})
    service = SummarizeService(client)
    from app.schemas import TurnIn

    response = service.summarize(
        SummarizeRequest(
            sessionNo="ws-1",
            previousSummary="用户在报修",
            messages=[TurnIn(role="USER", content="A栋，1单元101")],
        )
    )

    assert "PUMP" in response.summary
    prompt = client.calls[0][1]
    assert "previousSummary" in prompt
    assert "ws-1" in prompt


def test_profile_extracts_stable_preference():
    client = FakeClient(
        {
            "profile": {
                "preferredTicketTypeCode": "PUMP",
                "commonIntents": ["ticket.create"],
            },
            "changed": True,
            "confidence": 0.82,
            "reason": "用户多次选择水泵故障",
        }
    )
    service = ProfileService(client)

    response = service.extract(
        UserProfileRequest(
            userId="1",
            currentProfile={},
            sessionSummary="用户在建单，选择水泵故障",
            messages=[],
        )
    )

    assert response.changed is True
    assert response.profile["preferredTicketTypeCode"] == "PUMP"
    assert "preferredTicketTypeCode" in client.calls[0][0]


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


def test_recovery_clarifies_missing_critical_info():
    client = FakeClient(
        {
            "action": "CLARIFY",
            "capabilityId": "property.ticket.create",
            "args": {"description": "水管漏水"},
            "clarification": "请问是哪个楼栋和房间？",
            "missingFields": ["locationText"],
            "reason": "缺少位置",
        }
    )
    service = RouteService(client, Settings(deepseek_api_key="fake"))
    response = service.recover(
        RecoveryRequest(
            userMessage="报修：水管漏水",
            capabilityId="property.ticket.create",
            args={"description": "水管漏水"},
            errorCode="60001",
            errorMessage="locationText不能为空",
            errorCategory="VALIDATION",
            capabilities=[capability("property.ticket.create", read_only=False)],
        )
    )

    assert response.action == "CLARIFY"
    assert response.clarification == "请问是哪个楼栋和房间？"
    assert response.missingFields == ["locationText"]
    assert "locationText不能为空" in client.calls[0][1]


def test_recovery_can_choose_alternative_capability():
    client = FakeClient(
        {
            "action": "ALTERNATIVE",
            "capabilityId": "property.ticket.query",
            "args": {"status": "PENDING"},
            "reason": "用户其实想查询",
        }
    )
    service = RouteService(client, Settings(deepseek_api_key="fake"))
    response = service.recover(
        RecoveryRequest(
            userMessage="查一下我的工单",
            capabilityId="property.ticket.create",
            errorCode="CAPABILITY_NOT_FOUND",
            errorCategory="NOT_FOUND",
            capabilities=[
                capability("property.ticket.query"),
                capability("property.ticket.create", read_only=False),
            ],
        )
    )

    assert response.action == "ALTERNATIVE"
    assert response.capabilityId == "property.ticket.query"


def test_recovery_falls_back_to_no_capability():
    client = FakeClient(
        {
            "action": "NO_CAPABILITY",
            "clarification": "目前没有这个功能，我可以帮你查工单或建单。",
            "reason": "超出能力范围",
        }
    )
    service = RouteService(client, Settings(deepseek_api_key="fake"))
    response = service.recover(
        RecoveryRequest(
            userMessage="帮我订一张机票",
            capabilities=[capability("property.ticket.query")],
        )
    )

    assert response.action == "NO_CAPABILITY"
    assert "没有这个功能" in response.clarification


def test_recovery_rejects_hallucinated_capability():
    client = FakeClient(
        {
            "action": "ALTERNATIVE",
            "capabilityId": "property.ticket.delete_all",
            "args": {},
        }
    )
    service = RouteService(client, Settings(deepseek_api_key="fake"))
    response = service.recover(
        RecoveryRequest(
            userMessage="删掉所有工单",
            capabilityId="property.ticket.query",
            capabilities=[capability("property.ticket.query")],
        )
    )

    # 编造的能力必须被丢弃；回退到原能力或 NO_CAPABILITY，不能执行。
    assert response.capabilityId != "property.ticket.delete_all"
    assert response.action in {"RETRY", "NO_CAPABILITY"}


def test_low_confidence_requires_clarification():
    client = FakeClient(
        {
            "capabilityId": "contract.rule.list",
            "confidence": 0.2,
            "needClarification": False,
        }
    )
    service = RouteService(
        client, Settings(deepseek_api_key="fake", confidence_threshold=0.55)
    )

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
