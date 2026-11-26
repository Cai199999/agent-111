"""多 Agent 编排骨架：route → plan → dispatch → collect → verify。"""

from app.orchestrator.graph import Orchestrator
from app.orchestrator.dispatcher import MockDispatcher, PlanOnlyDispatcher

__all__ = ["Orchestrator", "PlanOnlyDispatcher", "MockDispatcher"]
