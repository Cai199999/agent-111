"""多 Agent 编排骨架测试：路由、计划、派发边界、结构化验收。"""

from __future__ import annotations

from fastapi.testclient import TestClient

from app.api import routes
from app.main import app
from app.orchestrator import MockDispatcher, Orchestrator, PlanOnlyDispatcher
from app.schemas import (
    OrchestrationCapability,
    OrchestrationRequest,
    PlanStep,
)


def capability(
    capability_id: str, display_name: str | None = None, agent_key: str | None = None
) -> OrchestrationCapability:
    return OrchestrationCapability(
        capabilityId=capability_id,
        agentKey=agent_key or capability_id.split(".", 1)[0],
        displayName=display_name or capability_id,
        description=display_name or capability_id,
    )


def request(
    message: str,
    capabilities: list[OrchestrationCapability],
    mode: str = "PLAN_ONLY",
    plan: list[PlanStep] | None = None,
) -> OrchestrationRequest:
    return OrchestrationRequest(
        traceId="trc-orchestrate",
        sessionNo="ws-orchestrate",
        userMessage=message,
        actorId="10001",
        tenantId="0",
        capabilities=capabilities,
        plan=plan or [],
        dispatchMode=mode,
    )


def test_plan_only_routes_contract_and_returns_dispatch_plan():
    orchestrator = Orchestrator(PlanOnlyDispatcher())

    response = orchestrator.run(
        request(
            "帮我审查这份物业服务合同",
            [
                capability("property.ticket.query", "工单查询"),
                capability("contract.review", "合同审查"),
            ],
        )
    )

    assert response.verdict == "DISPATCH_REQUIRED"
    assert response.capabilityId == "contract.review"
    assert [step.capabilityId for step in response.plan] == ["contract.review"]
    assert response.steps[0].status == "DISPATCH_REQUIRED"
    assert response.steps[0].agentKey == "contract"
    assert response.rounds == 1


def test_mock_dispatch_completes_without_network():
    orchestrator = Orchestrator(MockDispatcher())

    response = orchestrator.run(
        request(
            "下周三请两天年假",
            [
                capability("oa.request.draft", "请假申请", "oa-agent"),
                capability("contract.review", "合同审查", "agent-hetong"),
            ],
            mode="MOCK",
        )
    )

    assert response.verdict == "COMPLETE"
    assert response.capabilityId == "oa.request.draft"
    assert response.steps[0].status == "SUCCEEDED"
    assert response.steps[0].output["ok"] is True


def test_mock_failure_maps_to_failed_verdict():
    orchestrator = Orchestrator(MockDispatcher(failures={"oa.request.draft"}))

    response = orchestrator.run(
        request(
            "我要请假",
            [capability("oa.request.draft", "请假申请", "oa-agent")],
            mode="MOCK",
        )
    )

    assert response.verdict == "FAILED"
    assert "mock agent failed" in (response.clarification or "")


def test_missing_fields_map_to_needs_input():
    orchestrator = Orchestrator(
        MockDispatcher(missing={"oa.request.draft": ["startDate", "days"]})
    )

    response = orchestrator.run(
        request(
            "我要请假",
            [capability("oa.request.draft", "请假申请", "oa-agent")],
            mode="MOCK",
        )
    )

    assert response.verdict == "NEEDS_INPUT"
    assert "startDate" in (response.clarification or "")
    assert "days" in (response.clarification or "")


def test_ambiguous_message_does_not_pick_for_user():
    orchestrator = Orchestrator(PlanOnlyDispatcher())

    response = orchestrator.run(
        request(
            "帮我看看这个",
            [
                capability("oa.request.draft", "请假申请", "oa-agent"),
                capability("contract.review", "合同审查", "agent-hetong"),
            ],
        )
    )

    assert response.verdict == "NEEDS_INPUT"
    assert response.capabilityId is None
    assert "请确认" in (response.clarification or "")


def test_provided_plan_takes_precedence_over_router():
    orchestrator = Orchestrator(PlanOnlyDispatcher())

    response = orchestrator.run(
        request(
            "随便说点什么",
            [
                capability("oa.request.draft", "请假申请", "oa-agent"),
                capability("contract.review", "合同审查", "agent-hetong"),
            ],
            plan=[PlanStep(capabilityId="oa.request.draft", args={"days": 2})],
        )
    )

    assert response.verdict == "DISPATCH_REQUIRED"
    assert response.capabilityId == "oa.request.draft"
    assert response.plan[0].args == {"days": 2}


def test_unknown_plan_capability_fails_closed():
    orchestrator = Orchestrator(PlanOnlyDispatcher())

    response = orchestrator.run(
        request(
            "帮我处理",
            [capability("oa.request.draft", "请假申请", "oa-agent")],
            plan=[PlanStep(capabilityId="finance.reimburse.create", args={})],
        )
    )

    assert response.verdict == "FAILED"
    assert "未授权能力" in "；".join(response.notes)


def test_orchestrate_endpoint_uses_internal_token_and_mode():
    client = TestClient(app)
    headers = (
        {"X-Internal-Token": routes.settings.internal_token}
        if routes.settings.internal_token
        else {}
    )
    payload = {
        "traceId": "trc-http",
        "userMessage": "下周三请两天年假",
        "dispatchMode": "MOCK",
        "capabilities": [
            {
                "capabilityId": "oa.request.draft",
                "agentKey": "oa-agent",
                "displayName": "请假申请",
            }
        ],
    }

    response = client.post("/internal/v1/ai/orchestrate", json=payload, headers=headers)

    assert response.status_code == 200
    body = response.json()
    assert body["verdict"] == "COMPLETE"
    assert body["steps"][0]["status"] == "SUCCEEDED"

    bad_mode = client.post(
        "/internal/v1/ai/orchestrate",
        json={**payload, "dispatchMode": "KAFKA"},
        headers=headers,
    )
    assert bad_mode.status_code == 400
