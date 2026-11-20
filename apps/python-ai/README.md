# wuye-agent Python AI

主 Agent 的 LLM 路由服务。只做“用户消息 → capabilityId/args/confidence”的 JSON 决策，不执行工具、不访问子 Agent、不持有业务数据。

```text
Java BFF
  → POST /internal/v1/ai/route
  → Python AI（DeepSeek / OpenAI 兼容）
  → 返回严格 JSON
  → Java 校验能力白名单后执行
```

## 配置

复制根目录 `.env.example` 为 `.env`，至少填写：

```dotenv
DEEPSEEK_API_KEY=sk-...
DEEPSEEK_BASE_URL=https://api.deepseek.com
LLM_MODEL=deepseek-chat
INTERNAL_TOKEN_J2P=<与 Java 相同>
AI_HOST=127.0.0.1
AI_PORT=8700
LLM_TIMEOUT_SECONDS=8
LLM_MAX_TOKENS=800
LLM_CONFIDENCE_THRESHOLD=0.55
```

## 运行

```bash
# 根目录执行；会 source .env
bash scripts/start-python-ai.sh
bash scripts/stop-python-ai.sh

# 健康 / 就绪
curl http://127.0.0.1:8700/health
curl http://127.0.0.1:8700/ready
```

`/ready` 在未配置 `DEEPSEEK_API_KEY` 时返回 503；Java 侧仍可用规则路由。

## 测试

```bash
cd apps/python-ai
.venv/bin/python -m pytest -q
# 7/7 passed
```

## API

```http
POST /internal/v1/ai/route
X-Internal-Token: <INTERNAL_TOKEN_J2P>
Content-Type: application/json

{
  "traceId": "trc-...",
  "sessionNo": "ws-...",
  "userMessage": "我有哪些待处理工单？",
  "recentTurns": [],
  "capabilities": [
    {"capabilityId": "property.ticket.query", "displayName": "查询工单",
     "description": "查询当前 Actor 可见工单", "readOnly": true,
     "requiresConfirmation": false}
  ],
  "currentDomain": "property"
}
```

响应：

```json
{
  "capabilityId": "property.ticket.query",
  "intent": "ticket.query",
  "args": {"status": "PENDING"},
  "confidence": 0.93,
  "needClarification": false,
  "clarification": null,
  "reason": "用户询问待处理工单",
  "usage": {"promptTokens": 320, "completionTokens": 48, "totalTokens": 368}
}
```

## 已验证

- DeepSeek `deepseek-chat` 实网调用成功；
- `1栋大堂的灯不亮了` → `property.ticket.create`，经确认后 `SUCCEEDED`；
- `付款节点一般怎么审` → 低置信度，LLM 主动追问消歧；
- Java → Python 使用 HTTP/1.1（避免 Uvicorn 对 h2c 升级返回 400）。

## 用户画像

- `/internal/v1/ai/profile`：从当前会话摘要/最近消息提取稳定偏好；
- 只允许默认项目/楼栋/房间、常用工单类型/优先级、常用意图、备注；
- 不提取姓名/电话/身份证等敏感信息；无明确证据不修改；
- 画像全局使用，但不读取其他会话原文。

## 会话摘要

- `/internal/v1/ai/summarize`：接收当前会话 `previousSummary` + 最近消息，返回当前会话摘要；
- 只针对当前会话，不跨会话检索、不写知识库；
- 失败时保留 `previousSummary`，不清空已有摘要。

## 结果解释

- `/internal/v1/ai/explain`：接收 `userMessage/capabilityId/output`，返回简短中文解释；
- 解释只依据原始 output，不编造、不改写数据；
- 请求会带 `optionCatalog` 和 `codeLabels`，解释中枚举只输出中文（如“待处理”“普通”），不输出英文 code；
- LLM 不可用时回退为“返回 N 条记录”的确定性摘要；
- 原始 output 由 Java 侧另外原样透传给前端。

## 多轮与枚举

- 请求可带 `pending`（上一轮的能力/参数/缺失字段），LLM 会合并本轮回答，不重复追问；
- 请求可带 `optionCatalog`（ticketTypes/priorities/locationTypes 等），LLM 必须用其中的 `code`；
- 中文名（如“水泵故障”“普通”）映射到 code（`PUMP`/`NORMAL`），匹配不到则追问候选；
- 可选值来自物业 `masterdata.options`，不在 Python 侧硬编码。

## 计划与修正

- `/internal/v1/ai/route` 返回 `plan`（最多 3 步）、`missingFields`，并接收能力 `inputSchema`；
- 前置步骤只允许只读能力（如 `profile.get`），Java 执行后把结果合并进最终参数；
- 必填字段缺失时返回追问，不猜测；
- `/internal/v1/ai/repair` 接收子 Agent 的 `VALIDATION` 错误和原始参数，返回修正后的参数或追问；
- Java 在最终任务校验失败时调用 repair，最多重试一次；
- LLM 不可用时 Java 回退规则路由。

## 行为与安全

- 只允许从请求里的 `capabilities` 选 `capabilityId`；未知能力会被降级为追问。
- 置信度低于阈值强制追问，不猜域。
- 输出必须是 JSON；解析失败重试一次，仍失败返回 503，Java 回退规则。
- 委托令牌/子 Agent 凭据不进入 Python；Python 不直连子 Agent。
- 超时/失败由 Java 侧 30 秒熔断，避免每轮等待。
- `usage` 已记录到日志，后续可落审计/成本表。
