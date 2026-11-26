#!/usr/bin/env python3
"""全功能 E2E：真实 HTTP + SSE + MySQL + LLM（如已启用）。

用法：
  python3 -I scripts/e2e_full.py
  BASE_URL=http://127.0.0.1:8600 python3 -I scripts/e2e_full.py

只使用标准库；不重置数据库；结束时卸载动态安装的 Agent。
"""

from __future__ import annotations

import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone

BASE = os.getenv("BASE_URL", "http://127.0.0.1:8600").rstrip("/")
# 真实数据模式：工单号/处理人不写死 mock 值，改用运行时返回的 ticketId。
REAL = os.getenv("E2E_REAL") == "1"
RESULTS: list[tuple[str, bool, str, float]] = []


def check(name: str, ok: bool, detail: object = "") -> None:
    elapsed = 0.0
    RESULTS.append((name, bool(ok), str(detail)[:1200], elapsed))
    print(f"{'PASS' if ok else 'FAIL'}  {name}" + ("" if ok else f"  :: {str(detail)[:700]}"))


def section(title: str) -> None:
    print(f"\n== {title} ==")


class Client:
    def __init__(self, token: str | None = None):
        self.token = token

    def call(self, method: str, path: str, body=None, headers=None,
             raw: bool = False, timeout: float = 60.0):
        data = None if body is None else json.dumps(body).encode("utf-8")
        h = {"Accept": "application/json"}
        if body is not None:
            h["Content-Type"] = "application/json"
        if self.token:
            h["Authorization"] = "Bearer " + self.token
        if headers:
            h.update(headers)
        req = urllib.request.Request(BASE + path, data=data, headers=h, method=method)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                text = resp.read().decode("utf-8", "replace")
                status = resp.status
        except urllib.error.HTTPError as exc:
            text = exc.read().decode("utf-8", "replace")
            status = exc.code
        except Exception as exc:  # noqa: BLE001
            return 0, {"error": str(exc)}
        if raw:
            return status, text
        try:
            return status, json.loads(text) if text else {}
        except json.JSONDecodeError:
            return status, {"raw": text}

    def login(self, username: str, password: str) -> "Client":
        status, body = self.call("POST", "/api/v1/auth/login",
                                 {"username": username, "password": password})
        if status == 200 and body.get("code") == "OK":
            self.token = body["data"]["accessToken"]
            return self
        raise RuntimeError(f"login failed {username}: {status} {body}")

    def get(self, path: str, **kwargs):
        return self.call("GET", path, **kwargs)

    def post(self, path: str, body=None, **kwargs):
        return self.call("POST", path, body, **kwargs)

    def delete(self, path: str, **kwargs):
        return self.call("DELETE", path, **kwargs)


def body_data(result) -> object:
    status, body = result
    if status == 200 and isinstance(body, dict) and body.get("code") == "OK":
        return body.get("data")
    return None


def new_session(client: Client) -> str:
    data = body_data(client.post("/api/v1/chat/sessions", {"title": "e2e"}))
    return data["sessionNo"]


def parse_sse(text: str) -> list[tuple[str, dict]]:
    events: list[tuple[str, dict]] = []
    name = None
    data_lines: list[str] = []
    for line in text.splitlines():
        if line.startswith("event:"):
            name = line[6:].strip()
        elif line.startswith("data:"):
            data_lines.append(line[5:].lstrip())
        elif line == "" and name:
            raw = "\n".join(data_lines)
            try:
                payload = json.loads(raw) if raw else {}
            except json.JSONDecodeError:
                payload = {"raw": raw}
            events.append((name, payload))
            name = None
            data_lines = []
    return events


def sse(client: Client, session: str, content: str, timeout: float = 90.0) -> list[tuple[str, dict]]:
    body = json.dumps({
        "content": content,
        "clientMessageId": "e2e-" + uuid.uuid4().hex,
    }).encode("utf-8")
    headers = {
        "Accept": "text/event-stream",
        "Content-Type": "application/json",
        "Authorization": "Bearer " + (client.token or ""),
    }
    req = urllib.request.Request(
        BASE + f"/api/v1/chat/sessions/{session}/messages",
        data=body, headers=headers, method="POST")
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return parse_sse(resp.read().decode("utf-8", "replace"))


def events(events_list, name: str) -> list[dict]:
    return [payload for event, payload in events_list if event == name]


def first(events_list, name: str) -> dict:
    items = events(events_list, name)
    return items[0] if items else {}


def route_cap(events_list) -> str | None:
    return first(events_list, "route").get("capabilityId")


def has_status(events_list, statuses: set[str]) -> bool:
    return any(isinstance(payload, dict) and payload.get("status") in statuses
               for _, payload in events_list)


def task_no(events_list) -> str | None:
    for _, payload in events_list:
        if isinstance(payload, dict) and payload.get("taskNo"):
            return payload["taskNo"]
    return None


def all_task_nos(events_list) -> list[str]:
    result: list[str] = []
    for _, payload in events_list:
        if isinstance(payload, dict) and payload.get("taskNo"):
            task = payload["taskNo"]
            if task not in result:
                result.append(task)
    return result


def result_items(events_list) -> list[dict]:
    for name, payload in events_list:
        if name != "result" or not isinstance(payload, dict):
            continue
        output = payload.get("output")
        if isinstance(output, list):
            return [item for item in output if isinstance(item, dict)]
        if isinstance(output, dict) and isinstance(output.get("result"), list):
            return [item for item in output["result"] if isinstance(item, dict)]
    return []


def ticket_of(task: dict | None) -> tuple[str | None, str | None]:
    if not isinstance(task, dict):
        return None, None
    output = task.get("output")
    if not isinstance(output, dict):
        return None, None
    ticket = output.get("ticket")
    if isinstance(ticket, dict):
        return str(ticket.get("id") or "") or None, ticket.get("ticketNo")
    if output.get("ticketNo"):
        return str(output.get("id") or "") or None, output.get("ticketNo")
    return None, None


def confirm(client: Client, task: str, decision: str = "APPROVE") -> dict | None:
    return body_data(client.post(f"/api/v1/tasks/{task}/confirm",
                                {"decision": decision, "comment": "e2e"}))


def poll(client: Client, task: str, statuses: set[str], timeout: float = 15.0) -> dict | None:
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        last = body_data(client.get(f"/api/v1/tasks/{task}"))
        if last and last.get("status") in statuses:
            return last
        time.sleep(0.3)
    return last


def cleanup_dynamic(client: Client) -> None:
    for agent in ("inspection-agent", "policy-qa-agent", "customer-service-agent"):
        try:
            client.delete(f"/api/v1/admin/agents/{agent}")
        except Exception:  # noqa: BLE001
            pass


def has_cap(agents_json: str, capability: str) -> bool:
    return capability in agents_json


def main() -> int:
    started = time.time()
    root = Client()

    section("健康 / 静态资源")
    check("GET /health", root.get("/health")[0] == 200)
    check("GET /ready", root.get("/ready")[0] == 200)
    status, text = root.get("/", raw=True, headers={"Accept": "text/html"})
    check("UI index 可访问", status == 200 and "物业主 Agent" in text, (status, text[:200]))
    status, text = root.get("/js/app.js", raw=True)
    check("UI JS 可观测入口", status == 200 and "orchestration-badge" in text)
    check("UI 含动态 Agent 卸载按钮逻辑",
          status == 200 and "uninstallAgent" in text and "agent-remove" in text)
    check("UI CSS 可访问", root.get("/css/app.css")[0] == 200)

    section("登录 / RBAC")
    admin = Client().login("admin", "admin123")
    user = Client().login("user", "user123")
    worker = Client().login("worker", "worker123")
    check("admin 登录", bool(admin.token))
    check("user 登录", bool(user.token))
    check("worker 登录", bool(worker.token))

    admin_agents = json.dumps(body_data(admin.get("/api/v1/agents")), ensure_ascii=False)
    user_agents = json.dumps(body_data(user.get("/api/v1/agents")), ensure_ascii=False)
    worker_agents = json.dumps(body_data(worker.get("/api/v1/agents")), ensure_ascii=False)
    check("admin 可见合同 Agent", "contract-review" in admin_agents)
    check("user 不可见合同 Agent", "contract-review" not in user_agents)
    check("user 可见时空资产", "geo-asset-agent" in user_agents)
    check("user 无巡查写能力", "gis.patrol.create" not in user_agents)
    check("worker 可见巡查写能力", "gis.patrol.create" in worker_agents)
    check("user 访问管理看板被拒", user.get("/api/v1/admin/metrics/summary")[0] == 403)

    section("A2A Agent Card")
    status, public_card = root.get("/.well-known/agent-card.json", raw=True)
    check("公开 Agent Card 200", status == 200)
    check("公开 Card 含主 Agent", "wuye-main-agent" in public_card)
    check("公开 Card 标记 discovery-only", "discovery-only" in public_card)
    check("公开 Card 不含内部令牌", "internalToken" not in public_card)
    check("子 Agent Card 含 GIS 能力",
          has_cap(json.dumps(body_data(admin.get("/api/v1/agents/geo-asset-agent/card")),
                             ensure_ascii=False), "gis.parcel.query"))
    check("管理端 A2A 总览", body_data(admin.get("/api/v1/admin/a2a/summary")) is not None)

    section("平台自省 / 目录追问")
    s = new_session(admin)
    e = sse(admin, s, "检查时空资产agent目前有什么功能")
    text = json.dumps(e, ensure_ascii=False)
    check("聚焦时空资产：platform.catalog", route_cap(e) == "platform.catalog", e)
    check("聚焦时空资产：列出 3 项能力", "3 项能力" in text, text[:600])
    check("聚焦时空资产：含地块/图斑/巡查",
          all(x in text for x in ("gis.parcel.query", "gis.change.query", "gis.patrol.create")))

    e = sse(admin, s, "具体是什么功能")
    text = json.dumps(e, ensure_ascii=False)
    check("追问沿用上下文：platform.catalog", route_cap(e) == "platform.catalog", e)
    check("追问沿用上下文：仍含 gis.parcel.query", "gis.parcel.query" in text)
    check("追问不泛化问域", "您想了解哪个功能" not in text)

    e = sse(admin, s, "全部功能列出来")
    check("全部功能仍聚焦时空资产", "gis.patrol.create" in json.dumps(e, ensure_ascii=False))

    e = sse(admin, s, "A2A 现在支持什么？")
    text = json.dumps(e, ensure_ascii=False)
    check("A2A 自省 platform.a2a", route_cap(e) == "platform.a2a", e)
    check("A2A 标记 discovery-only", "discovery-only" in text)
    check("A2A jsonRpc=false", "\"jsonRpc\":false" in text or "\"jsonRpc\": false" in text)

    e = sse(admin, s, "现在记忆里有什么？")
    text = json.dumps(e, ensure_ascii=False)
    check("记忆自省 platform.memory", route_cap(e) == "platform.memory", e)
    check("记忆输出含 sessionSummary", "sessionSummary" in text)

    section("物业：查询 / 建单 / 派单 / 关闭 / 批量")
    s = new_session(admin)
    e = sse(admin, s, "现在还有哪些没处理的工单？")
    text = json.dumps(e, ensure_ascii=False)
    check("工单查询路由", route_cap(e) == "property.ticket.query", e)
    tickets = result_items(e)
    if REAL:
        check("工单查询成功", "SUCCEEDED" in text and bool(tickets), text[:800])
    else:
        check("工单查询成功", "SUCCEEDED" in text and "GD20261119000107" in text)
    pending_ids = [
        str(item.get("id")) for item in tickets
        if item.get("id")
        and str(item.get("statusCode") or item.get("status") or "PENDING") == "PENDING"
    ]

    e = sse(admin, s, "1栋大堂的灯不亮了，帮我报修")
    text = json.dumps(e, ensure_ascii=False)
    check("建单进入确认", route_cap(e) == "property.ticket.create" and "WAITING_CONFIRM" in text, e)
    create_task = task_no(e)
    check("建单生成任务号", bool(create_task))
    created_id = None
    if create_task:
        done = confirm(admin, create_task)
        check("建单确认后成功", (done or {}).get("status") == "SUCCEEDED", done)
        created_id, _ = ticket_of(done)

    if REAL and created_id:
        e = sse(admin, s, f"把工单 ID {created_id} 派给 10002")
        text = json.dumps(e, ensure_ascii=False)
        check("自然语言派单路由", route_cap(e) == "property.ticket.assign", e)
        check("派单带出 ticketNo", created_id in text, text[:800])
        check("派单带出 worker", "10002" in text, text[:800])
    else:
        e = sse(admin, s, "把3栋2单元水管漏水那个工单派给张师傅")
        text = json.dumps(e, ensure_ascii=False)
        check("自然语言派单路由", route_cap(e) == "property.ticket.assign", e)
        check("派单带出 ticketNo", "GD20261119000107" in text, text[:800])
        check("派单带出 worker", "10002" in text, text[:800])
    assign_task = task_no(e)
    if assign_task:
        done = confirm(admin, assign_task)
        check("派单确认后成功", (done or {}).get("status") == "SUCCEEDED", done)

    if REAL and pending_ids:
        close_id = pending_ids.pop(0)
        e = sse(admin, s, f"关闭工单 ID {close_id}")
        text = json.dumps(e, ensure_ascii=False)
        check("自然语言关闭路由", route_cap(e) == "property.ticket.close", e)
        check("关闭带出 ticketNo", close_id in text, text[:800])
    else:
        e = sse(admin, s, "把1栋大堂照明故障那个工单关掉")
        text = json.dumps(e, ensure_ascii=False)
        check("自然语言关闭路由", route_cap(e) == "property.ticket.close", e)
        check("关闭带出 ticketNo", "GD20261119000108" in text)
    close_task = task_no(e)
    if close_task:
        done = confirm(admin, close_task)
        if REAL:
            status = (done or {}).get("status")
            error = (done or {}).get("error") or {}
            # 真实子 Agent 对 PENDING 工单会按业务状态拒绝关闭（20002）；
            # 这里验证“已到达子 Agent 并完成确认”，成功或业务拒绝都算链路通过。
            check("关闭确认链路（含业务状态拒绝）",
                  status == "SUCCEEDED"
                  or (status == "FAILED" and error.get("code") == "20002"),
                  done)
        else:
            check("关闭确认后成功", (done or {}).get("status") == "SUCCEEDED", done)

    if REAL and len(pending_ids) >= 2:
        first_id, second_id = pending_ids[0], pending_ids[1]
        e = sse(admin, s,
                f"把工单 ID {first_id} 派给 10002，把工单 ID {second_id} 派给 10003")
        text = json.dumps(e, ensure_ascii=False)
        check("批量派单 batch", route_cap(e) == "batch.execute", e)
        batch_created = next(
            (payload.get("created") for name, payload in e if name == "batch"), 0)
        check("批量派单两张工单", batch_created == 2, text[:800])
        check("批量派单两个处理人", len(all_task_nos(e)) == 2, text[:800])
    else:
        e = sse(admin, s, "把水管漏水工单派给张师傅，道闸异常派给李师傅")
        text = json.dumps(e, ensure_ascii=False)
        check("批量派单 batch", route_cap(e) == "batch.execute", e)
        check("批量派单两张工单", "GD20261119000107" in text and "GD20261119000109" in text)
        check("批量派单两个处理人", "10002" in text and "10003" in text)
    batch_tasks = all_task_nos(e)
    check("批量生成两个任务", len(batch_tasks) == 2, batch_tasks)
    for task in batch_tasks:
        done = confirm(admin, task)
        check(f"批量任务确认成功 {task}", (done or {}).get("status") == "SUCCEEDED", done)

    section("合同：规则 / 审查 / 取消")
    s = new_session(admin)
    e = sse(admin, s, "合同审查规则有哪些？")
    text = json.dumps(e, ensure_ascii=False)
    check("合同规则路由", route_cap(e) == "contract.rule.list", e)
    check("合同规则返回", "R-CON-001" in text)

    e = sse(admin, s, "帮我审一下物业服务合同")
    text = json.dumps(e, ensure_ascii=False)
    check("自然语言合同审查路由", route_cap(e) == "contract.review", e)
    review_task = task_no(e)
    check("合同审查生成任务", bool(review_task))
    if review_task:
        done = poll(admin, review_task, {"SUCCEEDED"})
        check("合同审查最终成功", (done or {}).get("status") == "SUCCEEDED", done)
        check("合同审查风险数", ((done or {}).get("output") or {}).get("findingsCount") == 3, done)

    e = sse(admin, s, "审查合同 HT-2026-001")
    cancel_task = task_no(e)
    if cancel_task:
        cancelled = body_data(admin.post(f"/api/v1/tasks/{cancel_task}/cancel",
                                         {"reason": "e2e cancel"}))
        check("合同审查可取消", (cancelled or {}).get("status") == "CANCELLED", cancelled)

    section("时空资产：地块 / 图斑 / 巡查")
    s = new_session(admin)
    e = sse(admin, s, "江北新区 A-01 那个商业地块现在什么状态？")
    text = json.dumps(e, ensure_ascii=False)
    check("自然语言地块查询路由", route_cap(e) == "gis.parcel.query", e)
    check("地块唯一命中 P-2026-001", "P-2026-001" in text)
    parcel_output = first(e, "result").get("output")
    check("地块只返回 1 条", isinstance(parcel_output, list) and len(parcel_output) == 1,
          parcel_output)

    e = sse(admin, s, "最近有没有比较紧急的遥感变化图斑？")
    text = json.dumps(e, ensure_ascii=False)
    check("自然语言图斑查询路由", route_cap(e) == "gis.change.query", e)
    check("图斑返回高等级", "CH-2026-001" in text and "CH-2026-003" in text and "HIGH" in text)

    e = sse(admin, s, "安排人去江北 A-01 地块做一次变化图斑复核")
    text = json.dumps(e, ensure_ascii=False)
    check("自然语言巡查路由", route_cap(e) == "gis.patrol.create", e)
    check("巡查自动带出地块", "P-2026-001" in text)
    patrol_task = task_no(e)
    if patrol_task:
        done = confirm(admin, patrol_task)
        check("巡查确认后成功", (done or {}).get("status") == "SUCCEEDED", done)

    section("自修复 / 未安装能力兜底")
    s = new_session(admin)
    e = sse(admin, s, "报修：花园的喷泉坏了")
    text = json.dumps(e, ensure_ascii=False)
    check("缺关键信息不报错", "event:error" not in text and "INTERNAL_ERROR" not in text, e)
    check("缺关键信息为追问/确认",
          has_status(e, {"CLARIFIED", "WAITING_CONFIRM"}), e)

    e = sse(admin, s, "查询政策：耕地保护")
    text = json.dumps(e, ensure_ascii=False)
    check("未安装政策能力不报错", "INTERNAL_ERROR" not in text and "event:error" not in text, e)
    check("未安装政策能力有友好兜底",
          "安装" in text or "不可用" in text or "没有这个功能" in text, text[:600])

    section("动态安装：完整巡检 Agent")
    cleanup_dynamic(admin)
    installed = body_data(admin.post("/api/v1/admin/agents/install", {"template": "inspection"}))
    check("安装巡检 Agent", (installed or {}).get("agentKey") == "inspection-agent", installed)
    installed_agents = body_data(admin.get("/api/v1/agents")) or []
    inspection_view = next((a for a in installed_agents
                            if a.get("agentKey") == "inspection-agent"), None)
    check("巡检 Agent 出现在目录", inspection_view is not None)
    check("巡检 Agent 标记 dynamic=true",
          bool(inspection_view) and inspection_view.get("dynamic") is True, inspection_view)

    s = new_session(admin)
    e = sse(admin, s, "查询巡检报告")
    text = json.dumps(e, ensure_ascii=False)
    check("巡检查询路由", route_cap(e) == "inspect.report.query", e)
    check("巡检查询返回报告", "IR-2026-001" in text)

    e = sse(admin, s, "生成今天的巡检报告")
    text = json.dumps(e, ensure_ascii=False)
    check("巡检生成进入确认", route_cap(e) == "inspect.report.generate" and "WAITING_CONFIRM" in text, e)
    inspect_task = task_no(e)
    if inspect_task:
        confirmed = confirm(admin, inspect_task)
        check("巡检生成确认受理", (confirmed or {}).get("status") in {"PENDING", "RUNNING", "SUCCEEDED"}, confirmed)
        done = poll(admin, inspect_task, {"SUCCEEDED"})
        check("巡检生成最终成功", (done or {}).get("status") == "SUCCEEDED", done)
        check("巡检报告编号", ((done or {}).get("output") or {}).get("reportNo") == "IR-2026-001", done)

    e = sse(admin, s, "导出巡检报告")
    text = json.dumps(e, ensure_ascii=False)
    check("巡检导出路由", route_cap(e) == "inspect.report.export", e)
    export_task = task_no(e)
    if export_task:
        done = poll(admin, export_task, {"SUCCEEDED"})
        check("巡检导出最终成功", (done or {}).get("status") == "SUCCEEDED", done)
        check("巡检导出文件名",
              "巡检报告-20261121.xlsx" in json.dumps(done, ensure_ascii=False), done)

    cleanup_dynamic(admin)
    check("卸载巡检 Agent 后目录恢复",
          "inspection-agent" not in json.dumps(body_data(admin.get("/api/v1/agents")), ensure_ascii=False))

    section("企业 Agent：URL 安装 / 记忆注入")
    policy = body_data(admin.post("/api/v1/admin/agents/install",
                                  {"cardUrl": BASE + "/demo-agents/policy"}))
    check("URL 安装政策 Agent", (policy or {}).get("agentKey") == "policy-qa-agent", policy)
    s = new_session(admin)
    # 先产生一轮会话内容，等摘要生成后再问政策，验证 _memory 注入。
    sse(admin, s, "我有哪些待处理工单？")
    for _ in range(12):
        session_data = body_data(admin.get(f"/api/v1/chat/sessions/{s}"))
        if session_data and session_data.get("summary"):
            break
        time.sleep(0.5)
    e = sse(admin, s, "查询政策：耕地保护")
    text = json.dumps(e, ensure_ascii=False)
    check("政策问答路由", route_cap(e) == "policy.qa.ask", e)
    check("政策问答引用法条", "土地管理法" in text)
    policy_output = first(e, "result").get("output") or {}
    check("政策 Agent 使用主 Agent 记忆",
          isinstance(policy_output, dict) and policy_output.get("memoryUsed") is True,
          policy_output)

    customer = body_data(admin.post("/api/v1/admin/agents/install",
                                    {"cardUrl": BASE + "/demo-agents/customer"}))
    check("URL 安装客服 Agent", (customer or {}).get("agentKey") == "customer-service-agent", customer)
    e = sse(admin, s, "查询客服工单")
    text = json.dumps(e, ensure_ascii=False)
    check("客服工单路由", route_cap(e) == "customer.ticket.query", e)
    check("客服工单返回", "CS-2026-001" in text)

    cleanup_dynamic(admin)
    remaining = json.dumps(body_data(admin.get("/api/v1/agents")), ensure_ascii=False)
    check("卸载企业 Agent 后目录恢复",
          all(x not in remaining for x in ("policy-qa-agent", "customer-service-agent")))

    section("管理端 / 审计 / 工具 / 记忆")
    metrics = body_data(admin.get("/api/v1/admin/metrics/summary"))
    check("指标看板", metrics is not None and "tasks" in metrics)
    check("指标含 byAgent", metrics is not None and "byAgent" in metrics)
    check("运行告警", body_data(admin.get("/api/v1/admin/alerts")) is not None)
    check("人工介入队列", body_data(admin.get("/api/v1/human-tasks?status=PENDING&limit=5")) is not None)
    audit = json.dumps(body_data(admin.get("/api/v1/admin/audit-logs?limit=200")), ensure_ascii=False)
    check("审计含 LOGIN", "LOGIN" in audit)
    check("审计含 TASK_SUBMIT", "TASK_SUBMIT" in audit)

    tools = json.dumps(body_data(admin.get("/api/v1/tools")), ensure_ascii=False)
    check("工具目录含物业查询", "property.ticket.query" in tools)
    tool_task = body_data(admin.post("/api/v1/tools/property.ticket.query/execute", {}))
    check("工具执行成功", (tool_task or {}).get("status") == "SUCCEEDED", tool_task)

    memory = body_data(admin.get("/api/v1/profile/memory"))
    check("个人记忆接口", memory is not None and "profile" in memory)

    if close_task:
        events_data = body_data(admin.get(f"/api/v1/tasks/{close_task}/events?afterSeq=0"))
        check("任务事件时间线", events_data is not None and len(events_data.get("items", [])) > 0,
              events_data)

    section("清理")
    cleanup_dynamic(admin)
    elapsed = time.time() - started
    passed = sum(1 for _, ok, _, _ in RESULTS if ok)
    failed = len(RESULTS) - passed
    print(f"\n{'='*60}")
    print(f"E2E 结果：PASS={passed} FAIL={failed} 总耗时={elapsed:.1f}s")
    print(f"{'='*60}")
    if failed:
        print("\n失败项：")
        for name, ok, detail, _ in RESULTS:
            if not ok:
                print(f"- {name}: {detail[:800]}")
    report = {
        "time": datetime.now(timezone.utc).isoformat(),
        "base": BASE,
        "passed": passed,
        "failed": failed,
        "elapsedSeconds": round(elapsed, 1),
        "results": [
            {"name": name, "ok": ok, "detail": detail}
            for name, ok, detail, _ in RESULTS
        ],
    }
    out = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                       "run", "e2e-full-report.json")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w", encoding="utf-8") as fh:
        json.dump(report, fh, ensure_ascii=False, indent=2)
    print(f"报告：{out}")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
