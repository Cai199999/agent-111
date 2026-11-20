#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "== JS syntax =="
node --check "$ROOT/apps/java-bff/src/main/resources/static/js/app.js"

echo "== Java tests =="
mvn -f "$ROOT/apps/java-bff/pom.xml" test

echo "== Python tests =="
if [[ ! -x "$ROOT/apps/python-ai/.venv/bin/python" ]]; then
  echo "缺少 apps/python-ai/.venv；先执行 uv venv --python 3.13 apps/python-ai/.venv && uv pip install -r ..."
  exit 1
fi
cd "$ROOT/apps/python-ai"
.venv/bin/python -m pytest -q

echo "CI_OK"
