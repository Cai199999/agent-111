#!/usr/bin/env python3
"""飞轮样本离线评测。

数据源二选一：
- JSONL 文件（默认/测试 profile：data/flywheel/samples.jsonl）
- ADMIN API（mysql profile：GET /api/v1/admin/flywheel/export）

输出：
- 终端摘要
- Markdown 报告（--report）
- 候选数据集 JSONL（--dataset-out，label=null / UNREVIEWED）
- 原始指标 JSON（--json-out）

诚实边界：样本是“运行痕迹”，不是带金标的评测集；成功率不等于业务正确率。
"""

from __future__ import annotations

import argparse
import json
import math
import os
import sys
import urllib.request
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

DEFAULT_INPUT = "data/flywheel/samples.jsonl"


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    samples: list[dict[str, Any]] = []
    with path.open(encoding="utf-8") as handle:
        for line_no, line in enumerate(handle, start=1):
            line = line.strip()
            if not line:
                continue
            try:
                value = json.loads(line)
            except json.JSONDecodeError as exc:
                raise ValueError(f"{path}:{line_no} 不是合法 JSON：{exc}") from exc
            if isinstance(value, dict):
                samples.append(value)
    return samples


def fetch_api(base_url: str, token: str, limit: int) -> list[dict[str, Any]]:
    url = base_url.rstrip("/") + f"/api/v1/admin/flywheel/export?limit={limit}"
    request = urllib.request.Request(
        url,
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/json",
        },
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        payload = json.load(response)
    data = payload.get("data", payload) if isinstance(payload, dict) else payload
    return data if isinstance(data, list) else []


def dedupe(samples: Iterable[dict[str, Any]]) -> tuple[list[dict[str, Any]], int]:
    seen: set[str] = set()
    unique: list[dict[str, Any]] = []
    removed = 0
    for sample in samples:
        key = str(sample.get("eventId") or sample.get("sampleId") or "")
        if key and key in seen:
            removed += 1
            continue
        if key:
            seen.add(key)
        unique.append(sample)
    return unique, removed


def _parse_time(value: Any) -> datetime | None:
    if not value:
        return None
    try:
        return datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except ValueError:
        return None


def filter_time(
    samples: list[dict[str, Any]], since: str | None, until: str | None
) -> list[dict[str, Any]]:
    if not since and not until:
        return samples
    lower = _parse_time(since) if since else None
    upper = _parse_time(until) if until else None
    kept = []
    for sample in samples:
        occurred = _parse_time(sample.get("occurredAt"))
        if occurred is None:
            continue
        if lower is not None and occurred < lower:
            continue
        if upper is not None and occurred > upper:
            continue
        kept.append(sample)
    return kept


def percentile(values: list[float], ratio: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    if len(ordered) == 1:
        return float(ordered[0])
    position = (len(ordered) - 1) * ratio
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return float(ordered[lower])
    return ordered[lower] * (upper - position) + ordered[upper] * (position - lower)


def _duration(sample: dict[str, Any]) -> float:
    value = sample.get("durationMs")
    if isinstance(value, (int, float)):
        return float(value)
    return 0.0


def _error(sample: dict[str, Any]) -> tuple[str, str]:
    status = str(sample.get("status") or "")
    error = sample.get("error")
    if not isinstance(error, dict) or not error:
        # 用户拒绝/取消是业务终态，不一定带技术错误。
        if status == "REJECTED":
            return ("USER_REJECTED", "CONFIRM")
        if status == "CANCELLED":
            return ("USER_CANCELLED", "CONFIRM")
        return ("UNKNOWN", "UNKNOWN")
    code = str(error.get("code") or "UNKNOWN")
    category = str(error.get("category") or "UNKNOWN")
    return (code, category)


def compute_report(samples: list[dict[str, Any]]) -> dict[str, Any]:
    total = len(samples)
    statuses = Counter(str(sample.get("status") or "UNKNOWN") for sample in samples)
    succeeded = statuses.get("SUCCEEDED", 0)
    failed = statuses.get("FAILED", 0) + statuses.get("REJECTED", 0)
    durations = [_duration(sample) for sample in samples]
    error_codes = Counter(
        _error(sample)[0]
        for sample in samples
        if str(sample.get("status")) != "SUCCEEDED"
    )
    error_categories = Counter(
        _error(sample)[1]
        for sample in samples
        if str(sample.get("status")) != "SUCCEEDED"
    )

    def breakdown(key: str) -> list[dict[str, Any]]:
        groups: dict[str, list[dict[str, Any]]] = {}
        for sample in samples:
            groups.setdefault(str(sample.get(key) or "UNKNOWN"), []).append(sample)
        rows = []
        for name, items in groups.items():
            ok = sum(1 for item in items if item.get("status") == "SUCCEEDED")
            bad = sum(
                1 for item in items if item.get("status") in ("FAILED", "REJECTED")
            )
            rows.append(
                {
                    "key": name,
                    "total": len(items),
                    "succeeded": ok,
                    "failed": bad,
                    "successRate": round(ok / len(items), 4) if items else 0.0,
                    "avgDurationMs": (
                        round(sum(_duration(item) for item in items) / len(items), 1)
                        if items
                        else 0.0
                    ),
                }
            )
        rows.sort(key=lambda row: (-row["total"], row["key"]))
        return rows

    occurred = [
        str(sample.get("occurredAt")) for sample in samples if sample.get("occurredAt")
    ]
    return {
        "total": total,
        "uniqueTasks": len(
            {str(sample.get("taskNo")) for sample in samples if sample.get("taskNo")}
        ),
        "uniqueSessions": len(
            {
                str(sample.get("sessionNo"))
                for sample in samples
                if sample.get("sessionNo")
            }
        ),
        "statuses": dict(statuses),
        "successRate": round(succeeded / total, 4) if total else 0.0,
        "failureRate": round(failed / total, 4) if total else 0.0,
        "latencyMs": {
            "avg": round(sum(durations) / len(durations), 1) if durations else 0.0,
            "p50": round(percentile(durations, 0.50), 1),
            "p95": round(percentile(durations, 0.95), 1),
            "max": round(max(durations), 1) if durations else 0.0,
        },
        "errors": {
            "codes": dict(error_codes.most_common()),
            "categories": dict(error_categories.most_common()),
        },
        "byAgent": breakdown("agentKey"),
        "byCapability": breakdown("capabilityId"),
        "timeRange": {
            "from": min(occurred) if occurred else None,
            "to": max(occurred) if occurred else None,
        },
    }


def write_dataset(samples: list[dict[str, Any]], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as handle:
        for sample in samples:
            record = {
                "sampleId": sample.get("sampleId"),
                "taskNo": sample.get("taskNo"),
                "sessionNo": sample.get("sessionNo"),
                "agentKey": sample.get("agentKey"),
                "capabilityId": sample.get("capabilityId"),
                "input": sample.get("input") or {},
                "output": sample.get("output"),
                "error": sample.get("error") or {},
                "status": sample.get("status"),
                "durationMs": sample.get("durationMs") or 0,
                "occurredAt": sample.get("occurredAt"),
                # 运行痕迹不是金标：先留空，人工复核后再填。
                "label": None,
                "reviewStatus": "UNREVIEWED",
            }
            handle.write(json.dumps(record, ensure_ascii=False) + "\n")


def _rate(value: float) -> str:
    return f"{value * 100:.1f}%"


def render_markdown(
    report: dict[str, Any], source: str, dataset_path: str | None
) -> str:
    latency = report["latencyMs"]
    lines = [
        "# 飞轮样本离线报告",
        "",
        f"> 生成时间：{datetime.now(timezone.utc).isoformat()}",
        f"> 数据源：{source}",
        "> 说明：样本是运行痕迹，不是人工金标；成功率不等于业务正确率。",
        "",
        "## 1. 总览",
        "",
        "| 指标 | 值 |",
        "|---|---:|",
        f"| 样本数 | {report['total']} |",
        f"| 去重后任务数 | {report['uniqueTasks']} |",
        f"| 会话数 | {report['uniqueSessions']} |",
        f"| 成功率 | {_rate(report['successRate'])} |",
        f"| 失败/拒绝率 | {_rate(report['failureRate'])} |",
        f"| 平均耗时 | {latency['avg']} ms |",
        f"| P50 / P95 / Max | {latency['p50']} / {latency['p95']} / {latency['max']} ms |",
        f"| 时间范围 | {report['timeRange']['from']} → {report['timeRange']['to']} |",
        "",
        "## 2. 状态分布",
        "",
        "| 状态 | 数量 |",
        "|---|---:|",
    ]
    for status, count in sorted(report["statuses"].items()):
        lines.append(f"| {status} | {count} |")

    lines += ["", "## 3. 错误分布", "", "| 错误码 | 数量 |", "|---|---:|"]
    if report["errors"]["codes"]:
        for code, count in report["errors"]["codes"].items():
            lines.append(f"| {code} | {count} |")
    else:
        lines.append("| 无 | 0 |")
    lines += ["", "| 分类 | 数量 |", "|---|---:|"]
    if report["errors"]["categories"]:
        for category, count in report["errors"]["categories"].items():
            lines.append(f"| {category} | {count} |")
    else:
        lines.append("| 无 | 0 |")

    def table(title: str, rows: list[dict[str, Any]], key_label: str) -> None:
        lines.extend(
            [
                "",
                f"## {title}",
                "",
                f"| {key_label} | 总数 | 成功 | 失败 | 成功率 | 平均耗时 |",
                "|---|---:|---:|---:|---:|---:|",
            ]
        )
        if not rows:
            lines.append("| 无 | 0 | 0 | 0 | - | - |")
        for row in rows:
            lines.append(
                f"| {row['key']} | {row['total']} | {row['succeeded']} | "
                f"{row['failed']} | {_rate(row['successRate'])} | {row['avgDurationMs']} ms |"
            )

    table("4. 按 Agent", report["byAgent"], "Agent")
    table("5. 按能力", report["byCapability"], "能力")

    lines += [
        "",
        "## 6. 数据集",
        "",
        f"- 候选数据集：{dataset_path or '未导出'}",
        "- `label=null`、`reviewStatus=UNREVIEWED`；人工复核后才可作为评测/训练数据。",
        "",
        "## 7. 边界",
        "",
        "- 只统计主 Agent 自己产生的样本，不读取子 Agent 业务库；",
        "- 失败率包含用户拒绝、下游不可用、校验失败等不同语义；",
        "- 未接人工标注与金标评测，不能把本报告当作生产质量结论。",
        "",
    ]
    return "\n".join(lines)


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="飞轮样本离线评测")
    parser.add_argument("--input", default=DEFAULT_INPUT, help="JSONL 样本文件")
    parser.add_argument("--api", help="ADMIN API 基址，如 http://127.0.0.1:8600")
    parser.add_argument(
        "--token",
        default=os.getenv("WUYE_TOKEN", ""),
        help="ADMIN JWT；也可用环境变量 WUYE_TOKEN",
    )
    parser.add_argument(
        "--limit", type=int, default=1000, help="API 导出上限（<=2000）"
    )
    parser.add_argument("--since", help="只保留 occurredAt >= since（ISO8601）")
    parser.add_argument("--until", help="只保留 occurredAt <= until（ISO8601）")
    parser.add_argument("--report", help="Markdown 报告输出路径")
    parser.add_argument("--dataset-out", help="候选数据集 JSONL 输出路径")
    parser.add_argument("--json-out", help="原始指标 JSON 输出路径")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    if args.api:
        if not args.token:
            print("缺少 ADMIN Token：--token 或 WUYE_TOKEN", file=sys.stderr)
            return 2
        samples = fetch_api(args.api, args.token, max(1, min(args.limit, 2000)))
        source = f"ADMIN API {args.api} (limit={args.limit})"
    else:
        path = Path(args.input)
        if not path.exists():
            print(
                f"样本文件不存在：{path}（mysql profile 请用 --api）", file=sys.stderr
            )
            return 2
        samples = load_jsonl(path)
        source = str(path)

    samples, duplicates = dedupe(samples)
    samples = filter_time(samples, args.since, args.until)
    if args.since or args.until:
        source += f" (since={args.since or '-'}, until={args.until or '-'})"
    if not samples:
        print("没有样本可评测。", file=sys.stderr)
        return 1
    report = compute_report(samples)
    report["deduped"] = duplicates

    if args.dataset_out:
        write_dataset(samples, Path(args.dataset_out))
    if args.json_out:
        payload = {"source": source, "report": report}
        Path(args.json_out).parent.mkdir(parents=True, exist_ok=True)
        Path(args.json_out).write_text(
            json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8"
        )
    markdown = render_markdown(report, source, args.dataset_out)
    if args.report:
        Path(args.report).parent.mkdir(parents=True, exist_ok=True)
        Path(args.report).write_text(markdown, encoding="utf-8")
    print(markdown)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
