#!/usr/bin/env bash
set -euo pipefail

PORT="${JAVA_PORT:-8600}"
BASE="http://127.0.0.1:${PORT}"

echo "== health =="
curl -s "$BASE/health"
echo
echo "== ready =="
curl -s "$BASE/ready"
echo

echo "== create session =="
SESSION="$(curl -s -X POST "$BASE/api/v1/chat/sessions" \
  -H 'Content-Type: application/json' \
  -H 'X-Actor-Id: 10001' \
  -d '{"title":"smoke"}' | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["sessionNo"])')"
echo "session=$SESSION"

echo "== SSE: property query =="
curl -s -N -X POST "$BASE/api/v1/chat/sessions/$SESSION/messages" \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -H 'X-Actor-Id: 10001' \
  -d '{"content":"我有哪些待处理工单？","clientMessageId":"smoke-001"}' | head -24

echo
echo "== SSE: ambiguous knowledge =="
curl -s -N -X POST "$BASE/api/v1/chat/sessions/$SESSION/messages" \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -H 'X-Actor-Id: 10001' \
  -d '{"content":"查一下知识库","clientMessageId":"smoke-002"}' | head -16
