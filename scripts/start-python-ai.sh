#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ ! -f "$ROOT/.env" ]]; then
  echo "缺少 $ROOT/.env；请先 cp .env.example .env 并填写。"
  exit 1
fi
set -a
# shellcheck disable=SC1091
source "$ROOT/.env"
set +a

if [[ -z "${DEEPSEEK_API_KEY:-}" ]]; then
  echo "缺少 DEEPSEEK_API_KEY；请在 .env 填写后重试。"
  echo "也可以用 WUYE_AI_ENABLED=false 继续使用规则路由。"
  exit 1
fi

PORT="${AI_PORT:-8700}"
HOST="${AI_HOST:-127.0.0.1}"
APP_DIR="$ROOT/apps/python-ai"
VENV="$APP_DIR/.venv"
PID_FILE="$ROOT/run/python-ai.pid"
LOG_FILE="$ROOT/logs/python-ai.log"

mkdir -p "$ROOT/run" "$ROOT/logs"

if ss -ltn | awk '{print $4}' | grep -q ":${PORT}$"; then
  echo "端口 ${PORT} 已被占用，停止启动。请先复核 RESOURCES.md，不要清理占用者。"
  exit 1
fi

if [[ ! -x "$VENV/bin/python" ]]; then
  echo "创建 Python venv..."
  uv venv --python 3.13 "$VENV"
  uv pip install --python "$VENV/bin/python" -r "$APP_DIR/requirements.txt"
fi

echo "启动 Python AI：http://${HOST}:${PORT}/（model=${LLM_MODEL:-deepseek-chat}）"
nohup "$VENV/bin/uvicorn" app.main:app --host "$HOST" --port "$PORT" \
  --app-dir "$APP_DIR" >"$LOG_FILE" 2>&1 &
echo $! >"$PID_FILE"
echo "PID: $(cat "$PID_FILE")  日志: $LOG_FILE"
echo "提示：8700 为候选端口，启动前应已登记到 ~/Workspace/RESOURCES.md。"
