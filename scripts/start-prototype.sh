#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PORT="${JAVA_PORT:-8600}"
JAR="$ROOT/apps/java-bff/target/wuye-agent-bff-0.1.0-SNAPSHOT.jar"
PID_FILE="$ROOT/run/prototype.pid"
LOG_FILE="$ROOT/logs/prototype.log"

mkdir -p "$ROOT/run" "$ROOT/logs"

if ss -ltn | awk '{print $4}' | grep -q ":${PORT}$"; then
  echo "端口 ${PORT} 已被占用，停止启动。请先复核 RESOURCES.md，不要清理占用者。"
  exit 1
fi

if [[ ! -f "$JAR" ]]; then
  echo "构建原型..."
  mvn -q -f "$ROOT/apps/java-bff/pom.xml" -DskipTests package
fi

echo "启动原型：http://127.0.0.1:${PORT}/"
nohup java -jar "$JAR" --server.port="$PORT" >"$LOG_FILE" 2>&1 &
echo $! >"$PID_FILE"
echo "PID: $(cat "$PID_FILE")  日志: $LOG_FILE"
echo "提示：8600 为候选端口，启动前应已登记到 ~/Workspace/RESOURCES.md。"
