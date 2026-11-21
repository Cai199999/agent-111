#!/usr/bin/env bash
set -euo pipefail

# 清空本项目演示数据（只允许 wuye_agent_db）。
# 保留账号/角色/权限；删除会话、消息、任务、事件、审计、LLM 计量、画像、人工介入队列。
#
# 用法：
#   bash scripts/reset-demo-data.sh         # 交互确认
#   bash scripts/reset-demo-data.sh --yes   # 跳过确认（演示前重置）

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DB_NAME="wuye_agent_db"

if [[ ! -f "$ROOT/.env" ]]; then
  echo "缺少 $ROOT/.env"
  exit 1
fi

set -a
# shellcheck disable=SC1091
source "$ROOT/.env"
set +a

# 从 MYSQL_URL 解析主机/端口，避免误连其他库。
URL="${MYSQL_URL:-jdbc:mysql://127.0.0.1:3306/wuye_agent_db}"
HOST="$(echo "$URL" | sed -E 's#jdbc:mysql://([^:/]+).*#\1#')"
PORT="$(echo "$URL" | sed -nE 's#jdbc:mysql://[^:/]+:([0-9]+).*#\1#p')"
PORT="${PORT:-3306}"
URL_DB="$(echo "$URL" | sed -E 's#.*/([^?]+).*#\1#')"

if [[ "$URL_DB" != "$DB_NAME" ]]; then
  echo "拒绝执行：MYSQL_URL 指向 $URL_DB，不是本项目库 $DB_NAME。"
  exit 1
fi

echo "将清空 $DB_NAME 中的演示数据（账号/权限保留）。"
if [[ "${1:-}" != "--yes" ]]; then
  read -r -p "确认继续？输入 yes：" answer
  [[ "$answer" == "yes" ]] || { echo "已取消。"; exit 0; }
fi

MYSQL_PWD="${MYSQL_PASSWORD:-}" mysql -h"$HOST" -P"$PORT" -u"${MYSQL_USERNAME:-wuye_agent_app}" "$DB_NAME" <<'SQL'
SET FOREIGN_KEY_CHECKS = 0;
DELETE FROM wa_task_event;
DELETE FROM wa_task_binding;
DELETE FROM wa_human_intervention;
DELETE FROM wa_audit_log;
DELETE FROM wa_llm_usage;
DELETE FROM wa_chat_message;
DELETE FROM wa_chat_session;
DELETE FROM wa_user_memory_profile;
SET FOREIGN_KEY_CHECKS = 1;
SQL

echo "已清空演示数据。账号：admin/admin123、worker/worker123、user/user123"
