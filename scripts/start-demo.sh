#!/usr/bin/env bash
set -euo pipefail

# 一键启动面试演示环境：
#   - 强制 mock 子 Agent（不依赖 8402/8400 两个同级项目）
#   - 默认开启 DeepSeek LLM 路由；DEMO_AI=0 可退化为纯规则路由
#   - 启动前重新打包，避免演示到旧 jar
#   - 只操作本项目 PID 文件，不按进程名杀进程，不碰共享中间件
#
# 用法：
#   bash scripts/start-demo.sh          # LLM + mock 子 Agent
#   DEMO_AI=0 bash scripts/start-demo.sh # 离线兜底：纯规则路由
#   JAVA_PORT=8600 AI_PORT=8700 ...      # 可用环境变量覆盖端口

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA_PORT="${JAVA_PORT:-8600}"
AI_PORT="${AI_PORT:-8700}"
DEMO_AI="${DEMO_AI:-1}"

if [[ ! -f "$ROOT/.env" ]]; then
  echo "缺少 $ROOT/.env；请先 cp .env.example .env 并填写。"
  exit 1
fi

port_busy() {
  ss -ltn 2>/dev/null | awk '{print $4}' | grep -q ":$1$"
}

wait_http() {
  local url="$1" name="$2" tries="${3:-30}"
  for _ in $(seq 1 "$tries"); do
    if curl -sf "$url" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "等待 $name 超时：$url"
  return 1
}

echo "== 停止本项目已有进程（仅按 PID 文件） =="
bash "$ROOT/scripts/stop-prototype.sh" || true
bash "$ROOT/scripts/stop-python-ai.sh" || true

for _ in $(seq 1 10); do
  if ! port_busy "$JAVA_PORT" && ! port_busy "$AI_PORT"; then
    break
  fi
  sleep 1
done

if port_busy "$JAVA_PORT"; then
  echo "端口 $JAVA_PORT 已被其他进程占用，停止启动（不清理占用者）。"
  exit 1
fi
if [[ "$DEMO_AI" == "1" ]] && port_busy "$AI_PORT"; then
  echo "端口 $AI_PORT 已被其他进程占用，停止启动（不清理占用者）。"
  exit 1
fi

# 如果 .env 没有可用密钥，自动退化为规则路由，避免演示时启动失败。
AI_FLAG="$DEMO_AI"
if [[ "$DEMO_AI" == "1" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$ROOT/.env"
  set +a
  if [[ -z "${DEEPSEEK_API_KEY:-}" ]]; then
    echo "未配置 DEEPSEEK_API_KEY，自动切换到纯规则路由。"
    AI_FLAG="0"
  fi
fi

echo "== 重新打包 Java BFF =="
mvn -q -f "$ROOT/apps/java-bff/pom.xml" -DskipTests package

if [[ "$AI_FLAG" == "1" ]]; then
  echo "== 启动 Python AI（LLM 路由） =="
  AI_PORT="$AI_PORT" bash "$ROOT/scripts/start-python-ai.sh"
  wait_http "http://127.0.0.1:${AI_PORT}/health" "Python AI" 30
else
  echo "== 跳过 Python AI（纯规则路由） =="
fi

echo "== 启动 Java BFF（mock 子 Agent） =="
JAVA_PORT="$JAVA_PORT" AI_PORT="$AI_PORT" \
  WUYE_AGENT_MODE=mock WUYE_AI_ENABLED="$AI_FLAG" \
  bash "$ROOT/scripts/start-prototype.sh"

wait_http "http://127.0.0.1:${JAVA_PORT}/health" "Java BFF" 45

cat <<EOF

============================================================
演示环境已就绪
  打开：http://127.0.0.1:${JAVA_PORT}/
  账号：admin/admin123  worker/worker123  user/user123
  模式：子 Agent=MOCK  LLM=$( [[ "$AI_FLAG" == "1" ]] && echo 开启 || echo 关闭（规则路由） )
  日志：logs/prototype.log  logs/python-ai.log
  停止：bash scripts/stop-prototype.sh && bash scripts/stop-python-ai.sh
============================================================
建议先跑一遍冒烟：
  bash scripts/smoke-demo.sh
如需清空本项目演示数据（仅 wuye_agent_db）：
  bash scripts/reset-demo-data.sh --yes
EOF
