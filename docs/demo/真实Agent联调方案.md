# 真实 Agent 联调方案（物业 8402 / 合同 8400）

> 目标：验证“已完好的业务系统”能否通过 `agent-task-v1` 组装进主 Agent，并跑通 A2A。
> 原则：不改两个子 Agent 的代码/配置；不重启共享中间件；不把演示默认链路切到真实 Agent。

---

## 1. 已读取到的事实

| 项 | 物业 `agent-gongdan` | 合同 `agent-hetong` |
|---|---|---|
| 真实版本 | Manifest/Card `0.9.0` | Manifest/Card `0.7.0` |
| 独立 Agent 端口 | `8402`（`AGENT_SERVICE_PORT` 必填） | `8400`（Java BFF） |
| 配套 Python | `8300`（部分 AI 能力需要） | `8500`（合同审查需要） |
| 内部令牌 | `INTERNAL_TOKEN_P2J_CURRENT` | `INTERNAL_TOKEN` |
| 健康 | `/agent/v1/health`、`/agent/v1/ready` | `/health`、`/ready` |
| Manifest | `/agent/v1/manifest` | `/agent/v1/manifest` |
| Jar | `apps/java-bff/target/agent-gongdan-java-bff-0.0.1-SNAPSHOT.jar` | `apps/java-bff/target/agent-hetong-bff-0.1.0-SNAPSHOT.jar` |
| 启动方式 | `--spring.profiles.active=agent` | `scripts/start-java.sh` + `start-python.sh` |

主 Agent 侧：

- `.env` 的 `AGENT_PROPERTY_INTERNAL_TOKEN` 长度 64，和物业 `INTERNAL_TOKEN_P2J_CURRENT` 匹配；
- `AGENT_CONTRACT_INTERNAL_TOKEN` 长度 18，和合同 `dev-internal-token` 匹配；
- `HttpPropertyAdapter` / `HttpContractAdapter` 已实现；
- 物业 Adapter 版本已从 `0.8.0` 对齐到真实 `0.9.0`。

---

## 2. 前置检查

```bash
ss -ltn | grep -E ':(8400|8402)\b' || echo "8400/8402 free"
curl -s http://127.0.0.1:8402/agent/v1/health
curl -s http://127.0.0.1:8402/agent/v1/ready
curl -s http://127.0.0.1:8400/health
curl -s http://127.0.0.1:8400/ready
```

---

## 3. 启动真实 Agent（由你执行）

> 当前环境的 `cc-guard` 会阻断任何对这两个目录的 Bash 引用；需要你在自己的终端启动，或临时放行 hook。

### 3.1 物业独立 Agent（8402）

```bash
cd /home/admin1/Workspace/agent-gongdan

AGENT_SERVICE_PORT=8402 \
AGENT_CARD_URL=http://127.0.0.1:8402/agent/v1 \
ENV_FILE=/home/admin1/Workspace/agent-gongdan/.env \
.jdk/bin/java -jar apps/java-bff/target/agent-gongdan-java-bff-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=agent
```

如果部分能力需要 Python，再启动：

```bash
cd /home/admin1/Workspace/agent-gongdan/apps/python-ai
./.venv/bin/uvicorn main:app --host 127.0.0.1 --port 8300
```

### 3.2 合同 Agent（8400 + 8500）

```bash
cd /home/admin1/Workspace/agent-hetong

# 合同审查需要 Python AI
bash scripts/start-python.sh

# Java BFF（agent + user API 都在 8400）
bash scripts/start-java.sh
```

> 合同 `.env` 当前 `LLM_PROVIDER=deepseek`，需要外网；如果只想验证 A2A 只读能力，Java 8400 起来即可，`contract.review` 可能失败但不影响 `contract.rule.list`。

---

## 4. 主 Agent 侧契约 smoke（不启动主 Agent）

真实 Agent 起来后，在 `wuye-agent` 项目内执行：

```bash
python3 -I scripts/real_agent_smoke.py
```

它会用 `.env` 里的内部令牌，验证：

- 物业 health/ready/manifest；
- `property.ticket.query` 只读任务；
- 合同 health/ready/manifest；
- `contract.rule.list` 只读任务；
- 返回真实 `status/output/error`。

> 不写业务数据；不改两个 Agent 的库。

---

## 5. 主 Agent 以 HTTP 模式联调（独立实例）

不要覆盖 mock 演示。开一个独立端口 `8610`：

```bash
cd /home/admin1/Workspace/wuye-agent

WUYE_AGENT_MODE=http \
WUYE_AI_ENABLED=false \
JAVA_PORT=8610 \
bash scripts/start-prototype.sh
```

打开：

```text
http://127.0.0.1:8610/
admin/admin123
```

验证：

1. Agent 目录应出现真实物业/合同 Agent（不再是 Mock 文案）；
2. `我有哪些待处理工单？` → 真实 `property.ticket.query`；
3. `合同审查规则有哪些？` → 真实 `contract.rule.list`；
4. 如果 8402/8400 的 Manifest 版本/能力匹配，路由、确认、审计、事件应复用主 Agent 现有逻辑。

---

## 6. 预期风险

| 风险 | 处理 |
|---|---|
| 物业 token 不匹配 | 401；把主 Agent `.env` 的 `AGENT_PROPERTY_INTERNAL_TOKEN` 设为 `INTERNAL_TOKEN_P2J_CURRENT` |
| 合同 token 不匹配 | 401；合同 `.env` 的 `INTERNAL_TOKEN` 应与主 Agent 一致 |
| Manifest 版本差异 | 物业 0.9.0、合同 0.7.0；主 Agent 已对齐物业版本 |
| 合同 Python 8500 未起 | `contract.review` 失败；先只测 `contract.rule.list` |
| 物业部分能力依赖 Python 8300 | 只测 `ticket.query`；需要时再起 8300 |
| 共享中间件压力 | 只读 smoke，不做压测；不重启/清理 |
| 演示稳定性 | 真实联调使用 8610 独立实例；mock 演示仍用 8600 |

---

## 7. 成功标准

- `real_agent_smoke.py` 全部 PASS；
- 主 Agent 8610 能列出真实 Agent，并完成至少 1 个只读 A2A 任务；
- 写操作（建单/派单/确认）在独立实例验证通过，且确认令牌/审计/事件正常；
- 不修改两个子 Agent 的代码/配置/数据；
- 8600 的 mock 演示不受影响。
