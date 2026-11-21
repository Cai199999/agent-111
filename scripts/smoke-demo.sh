#!/usr/bin/env bash
set -euo pipefail

# 演示链路冒烟：登录 -> 查询 -> 建单确认 -> 异步合同审查 -> 管理看板/审计。
# 只读 + 本项目 API，不清理任何数据。失败会以非 0 退出。

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PORT="${JAVA_PORT:-8600}"
BASE="http://127.0.0.1:${PORT}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

pass=0
fail=0
check() {
  local name="$1" ok="$2"
  if [[ "$ok" == "1" ]]; then
    echo "  PASS  $name"
    pass=$((pass + 1))
  else
    echo "  FAIL  $name"
    fail=$((fail + 1))
  fi
}

json_get() {
  python3 -c "import json,sys; d=json.load(sys.stdin); print($1)" 2>/dev/null || true
}

echo "== 健康检查 =="
check "GET /health" "$(curl -sf "$BASE/health" >/dev/null && echo 1 || echo 0)"
check "GET /ready" "$(curl -sf "$BASE/ready" >/dev/null && echo 1 || echo 0)"

echo "== 登录 =="
curl -sf -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' >"$TMP/login.json" || true
TOKEN="$(json_get 'd["data"]["accessToken"]' <"$TMP/login.json")"
check "admin 登录" "$([[ -n "$TOKEN" ]] && echo 1 || echo 0)"
if [[ -z "$TOKEN" ]]; then
  echo "登录失败，终止。"
  exit 1
fi
AUTH="Authorization: Bearer $TOKEN"

echo "== A2A 发现层 =="
curl -sf "$BASE/.well-known/agent-card.json" >"$TMP/agent-card.json" || true
check "公开 Agent Card" \
  "$(grep -q 'wuye-main-agent' "$TMP/agent-card.json" && grep -q 'agent-task-v1' "$TMP/agent-card.json" && echo 1 || echo 0)"
check "Agent Card 不泄露内部令牌" \
  "$(grep -qi 'internalToken' "$TMP/agent-card.json" && echo 0 || echo 1)"
check "管理端 A2A 总览" \
  "$(curl -sf "$BASE/api/v1/admin/a2a/summary" -H "$AUTH" >/dev/null && echo 1 || echo 0)"

echo "== 可扩展多 Agent：时空资产 Agent =="
check "Agent 目录自动出现 geo-asset-agent" \
  "$(curl -sf "$BASE/api/v1/agents" -H "$AUTH" | grep -q 'geo-asset-agent' && echo 1 || echo 0)"
GIS_SESSION="$(curl -sf -X POST "$BASE/api/v1/chat/sessions" -H "$AUTH" \
  -H 'Content-Type: application/json' -d '{"title":"demo-gis-smoke"}' \
  | json_get 'd["data"]["sessionNo"]')"
curl -s -N -X POST "$BASE/api/v1/chat/sessions/$GIS_SESSION/messages" -H "$AUTH" \
  -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
  -d '{"content":"查询地块 P-2026-001","clientMessageId":"smoke-gis-1"}' >"$TMP/gis-query.sse"
check "地块查询路由 gis.parcel.query" \
  "$(grep -q 'gis.parcel.query' "$TMP/gis-query.sse" && echo 1 || echo 0)"
check "地块查询返回 P-2026-001" \
  "$(grep -q 'P-2026-001' "$TMP/gis-query.sse" && echo 1 || echo 0)"
curl -s -N -X POST "$BASE/api/v1/chat/sessions/$GIS_SESSION/messages" -H "$AUTH" \
  -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
  -d '{"content":"为地块 P-2026-001 创建巡查任务","clientMessageId":"smoke-gis-2"}' >"$TMP/gis-patrol.sse"
check "巡查任务进入 WAITING_CONFIRM" \
  "$(grep -q 'WAITING_CONFIRM' "$TMP/gis-patrol.sse" && echo 1 || echo 0)"
GIS_TASK="$(grep -o 'wt_[0-9_]*' "$TMP/gis-patrol.sse" | head -1 || true)"
if [[ -n "$GIS_TASK" ]]; then
  curl -sf -X POST "$BASE/api/v1/tasks/$GIS_TASK/confirm" -H "$AUTH" \
    -H 'Content-Type: application/json' -d '{"decision":"APPROVE","comment":"smoke"}' \
    >"$TMP/gis-confirm.json" || true
  check "巡查任务确认后 SUCCEEDED" \
    "$(json_get 'd["data"]["status"]' <"$TMP/gis-confirm.json" | grep -q SUCCEEDED && echo 1 || echo 0)"
else
  check "巡查任务生成任务号" 0
fi

echo "== 工单查询 =="
SESSION="$(curl -sf -X POST "$BASE/api/v1/chat/sessions" -H "$AUTH" \
  -H 'Content-Type: application/json' -d '{"title":"demo-smoke"}' \
  | json_get 'd["data"]["sessionNo"]')"
check "创建会话" "$([[ -n "$SESSION" ]] && echo 1 || echo 0)"

curl -s -N -X POST "$BASE/api/v1/chat/sessions/$SESSION/messages" -H "$AUTH" \
  -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
  -d '{"content":"我有哪些待处理工单？","clientMessageId":"smoke-query-1"}' >"$TMP/query.sse"
check "查询路由 property.ticket.query" \
  "$(grep -q '"capabilityId":"property.ticket.query"' "$TMP/query.sse" && echo 1 || echo 0)"
check "查询执行 SUCCEEDED" \
  "$(grep -q '"status":"SUCCEEDED"' "$TMP/query.sse" && echo 1 || echo 0)"

echo "== 建单 + 人工确认 =="
curl -s -N -X POST "$BASE/api/v1/chat/sessions/$SESSION/messages" -H "$AUTH" \
  -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
  -d '{"content":"报修：工单类型选 LIGHTING，位置1栋大堂，灯不亮","clientMessageId":"smoke-create-1"}' \
  >"$TMP/create.sse"
TASK="$(grep -o 'wt_[0-9_]*' "$TMP/create.sse" | head -1 || true)"
check "建单进入 WAITING_CONFIRM" \
  "$(grep -q '"status":"WAITING_CONFIRM"' "$TMP/create.sse" && echo 1 || echo 0)"
check "生成确认任务" "$([[ -n "$TASK" ]] && echo 1 || echo 0)"
check "确认令牌不出现子 Agent token" \
  "$(grep -qi 'confirmationToken' "$TMP/create.sse" && echo 0 || echo 1)"

if [[ -n "$TASK" ]]; then
  curl -sf -X POST "$BASE/api/v1/tasks/$TASK/confirm" -H "$AUTH" \
    -H 'Content-Type: application/json' -d '{"decision":"APPROVE","comment":"smoke"}' \
    >"$TMP/confirm.json" || true
  check "确认后 SUCCEEDED" \
    "$(json_get 'd["data"]["status"]' <"$TMP/confirm.json" | grep -q SUCCEEDED && echo 1 || echo 0)"
fi

echo "== 自修复：缺关键信息先追问 =="
HEAL_SESSION="$(curl -sf -X POST "$BASE/api/v1/chat/sessions" -H "$AUTH" \
  -H 'Content-Type: application/json' -d '{"title":"demo-heal-smoke"}' \
  | json_get 'd["data"]["sessionNo"]')"
curl -s -N -X POST "$BASE/api/v1/chat/sessions/$HEAL_SESSION/messages" -H "$AUTH" \
  -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
  -d '{"content":"报修：花园的喷泉坏了","clientMessageId":"smoke-heal-1"}' >"$TMP/heal.sse"
check "缺关键信息转为追问（CLARIFIED）" \
  "$(grep -q '"status":"CLARIFIED"' "$TMP/heal.sse" && echo 1 || echo 0)"
check "不把原始错误直接抛给用户" \
  "$(grep -q 'event:error' "$TMP/heal.sse" && echo 0 || echo 1)"

echo "== 异步合同审查 =="
curl -s -N -X POST "$BASE/api/v1/chat/sessions/$SESSION/messages" -H "$AUTH" \
  -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
  -d '{"content":"审查合同 HT-2026-001","clientMessageId":"smoke-contract-1"}' >"$TMP/contract.sse"
CTASK="$(grep -o 'wt_[0-9_]*' "$TMP/contract.sse" | head -1 || true)"
check "合同审查任务已提交" "$([[ -n "$CTASK" ]] && echo 1 || echo 0)"
if [[ -n "$CTASK" ]]; then
  for _ in $(seq 1 10); do
    curl -sf "$BASE/api/v1/tasks/$CTASK" -H "$AUTH" >"$TMP/ctask.json" || true
    if json_get 'd["data"]["status"]' <"$TMP/ctask.json" | grep -q SUCCEEDED; then
      break
    fi
    sleep 1
  done
  check "合同审查最终 SUCCEEDED" \
    "$(json_get 'd["data"]["status"]' <"$TMP/ctask.json" | grep -q SUCCEEDED && echo 1 || echo 0)"
fi

echo "== 管理端 =="
check "指标看板" "$(curl -sf "$BASE/api/v1/admin/metrics/summary" -H "$AUTH" >/dev/null && echo 1 || echo 0)"
check "运行告警" "$(curl -sf "$BASE/api/v1/admin/alerts" -H "$AUTH" >/dev/null && echo 1 || echo 0)"
check "人工介入队列" "$(curl -sf "$BASE/api/v1/human-tasks?status=PENDING&limit=5" -H "$AUTH" >/dev/null && echo 1 || echo 0)"
check "审计日志" "$(curl -sf "$BASE/api/v1/admin/audit-logs?limit=5" -H "$AUTH" >/dev/null && echo 1 || echo 0)"

echo
echo "结果：PASS=$pass FAIL=$fail"
[[ "$fail" -eq 0 ]]
