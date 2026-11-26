"""编排图中的派发边界。

架构约束：Python 侧不持有子 Agent 令牌、不直连子 Agent。
因此默认 {@link PlanOnlyDispatcher} 只产出派发计划（DISPATCH_REQUIRED），
由 Java BFF 执行；MOCK 仅用于本地演示与单测，不代表生产能力。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Protocol


class DispatchStatus:
    SUCCEEDED = "SUCCEEDED"
    FAILED = "FAILED"
    DISPATCH_REQUIRED = "DISPATCH_REQUIRED"


@dataclass
class DispatchResult:
    status: str
    output: Any = None
    error: str | None = None
    missing_fields: list[str] = field(default_factory=list)


class AgentDispatcher(Protocol):
    """派发适配器；实现可以是计划输出、mock，或未来的受控 HTTP 调用。"""

    def dispatch(
        self, capability_id: str, args: dict[str, Any], context: dict[str, Any]
    ) -> DispatchResult: ...


class PlanOnlyDispatcher:
    """只产出派发计划：真正调用子 Agent 的是 Java BFF。"""

    def dispatch(
        self, capability_id: str, args: dict[str, Any], context: dict[str, Any]
    ) -> DispatchResult:
        return DispatchResult(
            status=DispatchStatus.DISPATCH_REQUIRED,
            output={"capabilityId": capability_id, "args": args},
        )


class MockDispatcher:
    """确定性 mock：用于编排图单测与离线演示，不发网络请求。"""

    def __init__(
        self,
        failures: set[str] | None = None,
        missing: dict[str, list[str]] | None = None,
    ) -> None:
        self.failures = failures or set()
        self.missing = missing or {}

    def dispatch(
        self, capability_id: str, args: dict[str, Any], context: dict[str, Any]
    ) -> DispatchResult:
        if capability_id in self.failures:
            return DispatchResult(
                status=DispatchStatus.FAILED,
                error=f"mock agent failed: {capability_id}",
            )
        if capability_id in self.missing:
            return DispatchResult(
                status=DispatchStatus.SUCCEEDED,
                output={"ok": False},
                missing_fields=list(self.missing[capability_id]),
            )
        return DispatchResult(
            status=DispatchStatus.SUCCEEDED,
            output={"ok": True, "capabilityId": capability_id, "args": args},
        )
