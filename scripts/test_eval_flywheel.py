"""飞轮离线评测脚本的单元测试。

运行：
    apps/python-ai/.venv/bin/python -m pytest -q scripts/test_eval_flywheel.py
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import eval_flywheel as ef  # noqa: E402


def sample(
    sample_id: str,
    status: str,
    duration: int,
    error: dict | None = None,
    agent: str = "oa-agent",
    capability: str = "oa.request.draft",
) -> dict:
    return {
        "sampleId": sample_id,
        "eventId": f"evt-{sample_id}",
        "eventType": (
            "agent.task.completed" if status == "SUCCEEDED" else "agent.task.failed"
        ),
        "taskNo": f"task-{sample_id}",
        "sessionNo": "ws-1",
        "agentKey": agent,
        "capabilityId": capability,
        "actorId": "10001",
        "tenantId": "0",
        "status": status,
        "input": {"days": 2},
        "output": {"requestNo": "QJ-1"} if status == "SUCCEEDED" else None,
        "error": error or {},
        "durationMs": duration,
        "occurredAt": "2026-11-26T01:00:00Z",
    }


def test_compute_report_metrics():
    samples = [
        sample("1", "SUCCEEDED", 100),
        sample("2", "SUCCEEDED", 200),
        sample("3", "SUCCEEDED", 300),
        sample(
            "4",
            "FAILED",
            400,
            {
                "code": "DOWNSTREAM_UNAVAILABLE",
                "category": "DEPENDENCY",
            },
        ),
    ]

    report = ef.compute_report(samples)

    assert report["total"] == 4
    assert report["uniqueTasks"] == 4
    assert report["successRate"] == 0.75
    assert report["failureRate"] == 0.25
    assert report["latencyMs"]["avg"] == 250.0
    assert report["latencyMs"]["p50"] == 250.0
    assert report["latencyMs"]["p95"] == 385.0
    assert report["errors"]["codes"] == {"DOWNSTREAM_UNAVAILABLE": 1}
    assert report["byAgent"][0]["key"] == "oa-agent"
    assert report["byAgent"][0]["total"] == 4


def test_dedupe_by_event_id():
    samples = [sample("1", "SUCCEEDED", 100), sample("1", "SUCCEEDED", 100)]

    unique, duplicates = ef.dedupe(samples)

    assert len(unique) == 1
    assert duplicates == 1


def test_rejected_without_error_is_user_rejected():
    rejected = sample("1", "REJECTED", 100)

    report = ef.compute_report([rejected])

    assert report["errors"]["codes"] == {"USER_REJECTED": 1}
    assert report["errors"]["categories"] == {"CONFIRM": 1}


def test_filter_time_uses_occurred_at():
    early = sample("1", "SUCCEEDED", 100)
    early["occurredAt"] = "2026-11-26T00:00:00Z"
    late = sample("2", "SUCCEEDED", 100)
    late["occurredAt"] = "2026-11-26T10:00:00Z"

    kept = ef.filter_time([early, late], "2026-11-26T06:00:00Z", None)

    assert [item["sampleId"] for item in kept] == ["2"]


def test_dataset_export_keeps_label_unreviewed(tmp_path: Path):
    samples = [sample("1", "SUCCEEDED", 100)]
    output = tmp_path / "dataset.jsonl"

    ef.write_dataset(samples, output)

    record = json.loads(output.read_text(encoding="utf-8").splitlines()[0])
    assert record["sampleId"] == "1"
    assert record["label"] is None
    assert record["reviewStatus"] == "UNREVIEWED"
    assert record["input"] == {"days": 2}


def test_markdown_contains_sections(tmp_path: Path):
    samples = [sample("1", "SUCCEEDED", 100), sample("2", "FAILED", 200)]
    report = ef.compute_report(samples)

    markdown = ef.render_markdown(report, "unit-test", str(tmp_path / "dataset.jsonl"))

    assert "# 飞轮样本离线报告" in markdown
    assert "## 1. 总览" in markdown
    assert "## 5. 按能力" in markdown
    assert "oa.request.draft" in markdown
    assert "运行痕迹" in markdown
