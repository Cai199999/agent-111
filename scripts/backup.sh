#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ ! -f "$ROOT/.env" ]]; then
  echo "缺少 .env"
  exit 1
fi
set -a
# shellcheck disable=SC1091
source "$ROOT/.env"
set +a

mkdir -p "$ROOT/backups"
STAMP="$(date +%Y%m%d_%H%M%S)"
OUT="$ROOT/backups/wuye_agent_db_${STAMP}.sql.gz"

echo "备份 wuye_agent_db -> $OUT"
mysqldump -h127.0.0.1 -P3306 -u"${MYSQL_USERNAME}" -p"${MYSQL_PASSWORD}" \
  --single-transaction --routines --triggers wuye_agent_db | gzip >"$OUT"
echo "完成：$(du -h "$OUT" | awk '{print $1}')"
