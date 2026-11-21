#!/usr/bin/env python3
"""真实 Agent A2A 契约 smoke（只读，不写业务数据）。

前置：物业 8402 / 合同 8400 已启动。
用法：python3 -I scripts/real_agent_smoke.py

只读取本项目 .env 中的 baseUrl / token；不访问两个子 Agent 的文件系统。
"""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request
import uuid

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ENV_PATH = os.path.join(ROOT, ".env")
RESULTS: list[tuple[str, bool, str]] = []


def load_env() -> dict[str, str]:
    env: dict[str, str] = {}
    try:
        with open(ENV_PATH, encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                key, value = line.split("=", 1)
                env[key.strip()] = value.strip().strip("'\"")
    except OSError as exc:
        print(f"无法读取 .env：{exc}")
        sys.exit(2)
    return env


def check(name: str, ok: bool, detail: object = "") -> None:
    RESULTS.append((name, bool(ok), str(detail)[:900]))
    print(f"{'PASS' if ok else 'FAIL'}  {name}" + ("" if ok else f"  :: {str(detail)[:500]}"))


def call(method: str, url: str, token: str, body=None, actor: str = "10001",
         roles: str = "ADMIN", timeout: float = 15.0):
    data = None if body is None else json.dumps(body).encode("utf-8")
    headers = {
        "Accept": "application/json",
        "X-Actor-Id": actor,
        "X-Actor-Roles": roles,
        "X-Trace-Id": "trc_real_" + uuid.uuid4().hex[:12],
    }
    if token:
        headers["X-Internal-Token"] = token
    if body is not None:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            text = resp.read().decode("utf-8", "replace")
            status = resp.status
    except urllib.error.HTTPError as exc:
        text = exc.read().decode("utf-8", "replace")
        status = exc.code
    except Exception as exc:  # noqa: BLE001
        return 0, {"error": str(exc)}
    try:
        return status, json.loads(text) if text else {}
    except json.JSONDecodeError:
        return status, {"raw": text}


def main() -> int:
    env = load_env()
    property_base = env.get("AGENT_PROPERTY_BASE_URL", "http://127.0.0.1:8402").rstrip("/")
    property_token = env.get("AGENT_PROPERTY_INTERNAL_TOKEN", "")
    contract_base = env.get("AGENT_CONTRACT_BASE_URL", "http://127.0.0.1:8400").rstrip("/")
    contract_token = env.get("AGENT_CONTRACT_INTERNAL_TOKEN", "")

    print(f"property={property_base} token_len={len(property_token)}")
    print(f"contract={contract_base} token_len={len(contract_token)}")

    # 物业 health / ready / manifest
    status, body = call("GET", property_base + "/agent/v1/health", property_token)
    check("物业 health", status == 200, (status, body))
    status, body = call("GET", property_base + "/agent/v1/ready", property_token)
    check("物业 ready", status == 200, (status, body))
    status, manifest = call("GET", property_base + "/agent/v1/manifest", property_token)
    caps = manifest.get("capabilities") if isinstance(manifest, dict) else None
    check("物业 manifest", status == 200 and isinstance(caps, list), (status, manifest))
    if isinstance(manifest, dict):
        print(f"  物业版本={manifest.get('version')} 能力数={len(caps or [])}")

    # 物业只读任务
    status, body = call("POST", property_base + "/agent/v1/tasks", property_token, {
        "taskType": "ticket.query",
        "input": {"limit": 3},
        "clientTaskId": "real-smoke-" + uuid.uuid4().hex,
        "executionMode": "SYNC",
    })
    check("物业 ticket.query", status == 200 and body.get("status") == "SUCCEEDED",
          (status, body))

    # 合同 health / ready / manifest
    status, body = call("GET", contract_base + "/health", contract_token)
    check("合同 health", status == 200, (status, body))
    status, body = call("GET", contract_base + "/ready", contract_token)
    check("合同 ready", status == 200, (status, body))
    status, manifest = call("GET", contract_base + "/agent/v1/manifest", contract_token)
    caps = manifest.get("capabilities") if isinstance(manifest, dict) else None
    check("合同 manifest", status == 200 and isinstance(caps, list), (status, manifest))
    if isinstance(manifest, dict):
        print(f"  合同版本={manifest.get('version')} 能力数={len(caps or [])}")

    # 合同只读任务
    status, body = call("POST", contract_base + "/agent/v1/tasks", contract_token, {
        "taskType": "contract.rule.list",
        "input": {},
        "clientTaskId": "real-smoke-" + uuid.uuid4().hex,
        "executionMode": "SYNC",
    })
    check("合同 contract.rule.list", status == 200 and body.get("status") == "SUCCEEDED",
          (status, body))

    passed = sum(1 for _, ok, _ in RESULTS if ok)
    failed = len(RESULTS) - passed
    print(f"\n结果：PASS={passed} FAIL={failed}")
    if failed:
        print("\n失败项：")
        for name, ok, detail in RESULTS:
            if not ok:
                print(f"- {name}: {detail}")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
