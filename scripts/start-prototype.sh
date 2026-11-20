#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ ! -f "$ROOT/.env" ]]; then
  echo "缺少 $ROOT/.env；请先 cp .env.example .env 并填写真实值。"
  exit 1
fi

# 允许命令行环境变量覆盖 .env（否则 source 会把 WUYE_AI_ENABLED=true 覆盖回 false）
PRESET_PROFILE="${SPRING_PROFILES_ACTIVE:-}"
PRESET_MODE="${WUYE_AGENT_MODE:-}"
PRESET_AI="${WUYE_AI_ENABLED:-}"
PRESET_PORT="${JAVA_PORT:-}"

set -a
# shellcheck disable=SC1091
source "$ROOT/.env"
set +a

[[ -n "$PRESET_PROFILE" ]] && export SPRING_PROFILES_ACTIVE="$PRESET_PROFILE"
[[ -n "$PRESET_MODE" ]] && export WUYE_AGENT_MODE="$PRESET_MODE"
[[ -n "$PRESET_AI" ]] && export WUYE_AI_ENABLED="$PRESET_AI"
[[ -n "$PRESET_PORT" ]] && export JAVA_PORT="$PRESET_PORT"

: "${SPRING_PROFILES_ACTIVE:=mysql}"
export SPRING_PROFILES_ACTIVE
echo "profile=${SPRING_PROFILES_ACTIVE} adapterMode=${WUYE_AGENT_MODE:-mock} aiEnabled=${WUYE_AI_ENABLED:-false}"

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
