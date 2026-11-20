#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PID_FILE="$ROOT/run/prototype.pid"

if [[ ! -f "$PID_FILE" ]]; then
  echo "没有找到 $PID_FILE，原型可能未启动。"
  exit 0
fi

PID="$(cat "$PID_FILE")"
if kill -0 "$PID" 2>/dev/null; then
  kill "$PID"
  echo "已停止原型进程 $PID"
else
  echo "进程 $PID 不存在"
fi
rm -f "$PID_FILE"
