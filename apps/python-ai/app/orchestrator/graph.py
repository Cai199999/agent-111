"""LangGraph 多 Agent 编排骨架。

第一阶段只冻结图结构与状态口径：
  route（选能力） → plan（定步骤） → dispatch（派发/计划） → collect → verify（结构化验收）

明确不做：
- 不在 Python 侧直连子 Agent（dispatch 默认为 PLAN_ONLY）；
- 不做 LLM 参数抽取（后续复用现有 /route 与 /repair 能力）；
- 不做自动重试循环（避免在骨架阶段引入不可控的多轮调用）。
"""

from __future__ import annotations

from collections import Counter
from typing import Any, TypedDict

from langgraph.graph import END, START, StateGraph

from app.orchestrator.dispatcher import (
    AgentDispatcher,
    DispatchResult,
    DispatchStatus,
    PlanOnlyDispatcher,
)
from app.schemas import (
    OrchestrationRequest,
    OrchestrationResponse,
    OrchestrationStepResult,
    OrchestrationVerifyResponse,
    PlanStep,
)

# 领域弱先验：只用于骨架阶段的可解释路由；正式路由仍由 Java 的 LLM + 规则完成。
_DOMAIN_HINTS: dict[str, tuple[str, ...]] = {
    "contract": (
        "合同",
        "甲方",
        "乙方",
        "违约",
        "付款节点",
        "审查",
        "盖章",
        "保密",
        "服务费",
    ),
    "oa": (
        "请假",
        "年假",
        "调休",
        "会议室",
        "用印",
        "采购",
        "公告",
        "归档",
        "请示",
        "报告",
        "报销",
        "审批",
    ),
    "property": ("报修", "工单", "派单", "电梯", "漏水", "灯", "保洁", "保安"),
    "gis": ("地块", "图斑", "遥感", "巡查"),
}


class OrchestrationState(TypedDict, total=False):
    request: dict[str, Any]
    route: dict[str, Any]
    plan: list[dict[str, Any]]
    steps: list[dict[str, Any]]
    notes: list[str]
    verdict: str
    clarification: str | None
    rounds: int


def _domain(capability_id: str) -> str:
    return capability_id.split(".", 1)[0] if capability_id else ""


def _score(capability: dict[str, Any], message: str) -> int:
    """确定性打分：领域关键词 3 分/个，显示名整词命中 5 分，字符覆盖最多 5 分。"""
    capability_id = str(capability.get("capabilityId") or "")
    score = 0
    for hint in _DOMAIN_HINTS.get(_domain(capability_id), ()):
        if hint in message:
            score += 3
    name = str(capability.get("displayName") or "")
    if name and name in message:
        score += 5
    overlap = sum(1 for char in set(name) if char.strip() and char in message)
    return score + min(overlap, 5)


def _clarify_text(capabilities: list[dict[str, Any]]) -> str:
    options = []
    for capability in capabilities[:5]:
        name = capability.get("displayName") or capability.get("capabilityId")
        options.append(f"{name}（{capability.get('capabilityId')}）")
    return "请确认要处理哪类事务：" + "、".join(options)


def _route_node(state: OrchestrationState) -> dict[str, Any]:
    request = state.get("request", {})
    message = str(request.get("userMessage") or "")
    capabilities = list(request.get("capabilities") or [])
    provided_plan = list(request.get("plan") or [])

    if provided_plan:
        first = provided_plan[0]
        return {
            "route": {
                "capabilityId": first.get("capabilityId"),
                "args": first.get("args") or {},
                "needsClarification": False,
                "reason": "PROVIDED_PLAN",
            }
        }

    if not capabilities:
        return {
            "route": {
                "capabilityId": None,
                "args": {},
                "needsClarification": True,
                "clarification": "当前没有可用能力，请先安装或授权子 Agent。",
                "reason": "NO_CAPABILITY",
            }
        }

    if len(capabilities) == 1:
        only = capabilities[0]
        return {
            "route": {
                "capabilityId": only.get("capabilityId"),
                "args": {},
                "needsClarification": False,
                "reason": "SINGLE_CAPABILITY",
            }
        }

    ranked = sorted(
        capabilities,
        key=lambda item: (-_score(item, message), str(item.get("capabilityId"))),
    )
    best = ranked[0]
    best_score = _score(best, message)
    if best_score <= 0:
        return {
            "route": {
                "capabilityId": None,
                "args": {},
                "needsClarification": True,
                "clarification": _clarify_text(capabilities),
                "reason": "AMBIGUOUS",
            }
        }

    # 跨域同分视为歧义，不替用户选择；同域同分按 capabilityId 稳定排序。
    if len(ranked) > 1 and _score(ranked[1], message) == best_score:
        if _domain(str(ranked[1].get("capabilityId"))) != _domain(
            str(best.get("capabilityId"))
        ):
            return {
                "route": {
                    "capabilityId": None,
                    "args": {},
                    "needsClarification": True,
                    "clarification": _clarify_text([best, ranked[1]]),
                    "reason": "CROSS_DOMAIN_TIE",
                }
            }

    return {
        "route": {
            "capabilityId": best.get("capabilityId"),
            "args": {},
            "needsClarification": False,
            "reason": f"KEYWORD_SCORE:{best_score}",
        }
    }


def _after_route(state: OrchestrationState) -> str:
    return "clarify" if state.get("route", {}).get("needsClarification") else "plan"


def _plan_node(state: OrchestrationState) -> dict[str, Any]:
    request = state.get("request", {})
    capabilities = list(request.get("capabilities") or [])
    by_id = {str(item.get("capabilityId")): item for item in capabilities}
    notes = list(state.get("notes") or [])

    provided = list(request.get("plan") or [])
    if provided:
        raw_steps = provided
    else:
        route = state.get("route", {})
        raw_steps = [
            {
                "capabilityId": route.get("capabilityId"),
                "args": route.get("args") or {},
            }
        ]

    steps: list[dict[str, Any]] = []
    for raw in raw_steps:
        capability_id = str(raw.get("capabilityId") or "")
        if capability_id not in by_id:
            notes.append(f"忽略未授权能力：{capability_id}")
            continue
        steps.append(
            {
                "capabilityId": capability_id,
                "args": raw.get("args") or {},
            }
        )

    if not steps:
        return {
            "plan": [],
            "notes": notes,
            "verdict": "FAILED",
            "clarification": "计划中的能力均不可用，请检查 Agent 安装与权限。",
        }
    return {"plan": steps, "notes": notes}


def _after_plan(state: OrchestrationState) -> str:
    return "dispatch" if state.get("plan") else "verify"


def _dispatch_node(
    state: OrchestrationState, dispatcher: AgentDispatcher
) -> dict[str, Any]:
    request = state.get("request", {})
    context = {
        "traceId": request.get("traceId"),
        "sessionNo": request.get("sessionNo"),
        "actorId": request.get("actorId"),
        "tenantId": request.get("tenantId"),
    }
    agent_by_capability = {
        str(item.get("capabilityId")): item.get("agentKey")
        for item in (request.get("capabilities") or [])
    }

    steps: list[dict[str, Any]] = []
    for step in state.get("plan") or []:
        capability_id = str(step.get("capabilityId"))
        result: DispatchResult = dispatcher.dispatch(
            capability_id, dict(step.get("args") or {}), context
        )
        steps.append(
            {
                "capabilityId": capability_id,
                "agentKey": agent_by_capability.get(capability_id),
                "status": result.status,
                "output": result.output,
                "error": result.error,
                "missingFields": list(result.missing_fields),
            }
        )
    return {"steps": steps, "rounds": int(state.get("rounds") or 0) + 1}


def _collect_node(state: OrchestrationState) -> dict[str, Any]:
    steps = list(state.get("steps") or [])
    statuses = Counter(str(step.get("status")) for step in steps)
    notes = list(state.get("notes") or [])
    notes.append(f"steps={len(steps)} statuses={dict(statuses)}")
    return {"notes": notes}


def _verify_node(state: OrchestrationState) -> dict[str, Any]:
    steps = list(state.get("steps") or [])
    notes = list(state.get("notes") or [])
    if not steps:
        return {
            "verdict": "FAILED",
            "clarification": state.get("clarification") or "没有可执行步骤。",
            "notes": notes,
        }

    failed = [step for step in steps if step.get("status") == DispatchStatus.FAILED]
    if failed:
        detail = "；".join(
            f"{step.get('capabilityId')}: {step.get('error') or '执行失败'}"
            for step in failed
        )
        return {
            "verdict": "FAILED",
            "clarification": f"执行失败：{detail}",
            "notes": notes,
        }

    missing: list[str] = []
    for step in steps:
        fields = list(step.get("missingFields") or [])
        output = step.get("output")
        if isinstance(output, dict):
            output_missing = output.get("missingFields") or output.get("missing")
            if isinstance(output_missing, list):
                fields.extend(str(item) for item in output_missing)
        if fields:
            unique = list(dict.fromkeys(fields))
            missing.append(f"{step.get('capabilityId')}: {'、'.join(unique)}")
    if missing:
        return {
            "verdict": "NEEDS_INPUT",
            "clarification": "还需要补充：" + "；".join(missing),
            "notes": notes,
        }

    if any(step.get("status") == DispatchStatus.DISPATCH_REQUIRED for step in steps):
        return {"verdict": "DISPATCH_REQUIRED", "notes": notes}

    return {"verdict": "COMPLETE", "notes": notes}


def _clarify_node(state: OrchestrationState) -> dict[str, Any]:
    route = state.get("route", {})
    return {
        "verdict": "NEEDS_INPUT",
        "clarification": route.get("clarification") or "请补充要处理的事项。",
        "plan": [],
        "steps": [],
    }


def verify_results(
    steps: list[OrchestrationStepResult], notes: list[str] | None = None
) -> OrchestrationVerifyResponse:
    """Java 执行后回传步骤结果，只跑 collect + verify，不再路由/派发。"""
    state: OrchestrationState = {
        "steps": [step.model_dump() for step in steps],
        "notes": list(notes or []),
    }
    collected = _collect_node(state)
    merged: OrchestrationState = {**state, **collected}
    verified = _verify_node(merged)
    return OrchestrationVerifyResponse(
        verdict=str(verified.get("verdict") or "FAILED"),
        clarification=verified.get("clarification"),
        notes=list(verified.get("notes") or []),
    )


def build_graph(dispatcher: AgentDispatcher) -> Any:
    graph = StateGraph(OrchestrationState)
    graph.add_node("route", _route_node)
    graph.add_node("clarify", _clarify_node)
    graph.add_node("plan", _plan_node)
    graph.add_node("dispatch", lambda state: _dispatch_node(state, dispatcher))
    graph.add_node("collect", _collect_node)
    graph.add_node("verify", _verify_node)

    graph.add_edge(START, "route")
    graph.add_conditional_edges(
        "route", _after_route, {"clarify": "clarify", "plan": "plan"}
    )
    graph.add_edge("clarify", END)
    graph.add_conditional_edges(
        "plan", _after_plan, {"dispatch": "dispatch", "verify": "verify"}
    )
    graph.add_edge("dispatch", "collect")
    graph.add_edge("collect", "verify")
    graph.add_edge("verify", END)
    return graph.compile()


class Orchestrator:
    """编排服务入口：构建一次图，按请求运行。"""

    def __init__(self, dispatcher: AgentDispatcher | None = None) -> None:
        self.dispatcher = dispatcher or PlanOnlyDispatcher()
        self.graph = build_graph(self.dispatcher)

    def run(self, request: OrchestrationRequest) -> OrchestrationResponse:
        state = self.graph.invoke(
            {
                "request": request.model_dump(),
                "notes": [],
                "rounds": 0,
            }
        )
        route = state.get("route") or {}
        plan = [PlanStep(**step) for step in (state.get("plan") or [])]
        steps = [OrchestrationStepResult(**step) for step in (state.get("steps") or [])]
        return OrchestrationResponse(
            verdict=str(state.get("verdict") or "FAILED"),
            capabilityId=route.get("capabilityId"),
            args=dict(route.get("args") or {}),
            plan=plan,
            steps=steps,
            clarification=state.get("clarification"),
            notes=list(state.get("notes") or []),
            rounds=int(state.get("rounds") or 0),
        )
