# A2A 渐进架构：核心稳定，协议做薄

> 面试主线：**不把平台绑死在某一个协议上。**
> 内部先把“任务、确认、审计、人工兜底”做成稳定内核；
> 外部用一层很薄的 A2A facade 做发现和协议适配；标准演进时只换 facade，不动内核。

---

## 一、为什么不做“重适配”

Google A2A 的完整落地包括：

- Agent Card 发现；
- JSON-RPC `message/send`、`tasks/get`、`tasks/cancel`、`tasks/resubscribe`；
- message/part/artifact 模型；
- push notification；
- 认证/授权/多租户。

两天内全做，风险是：

1. 演示链路被协议细节拖垮；
2. 内部任务模型被外部协议反向污染；
3. 真实子 Agent 还没联调，先做标准适配没有收益。

所以选择：

> **先做“发现层”与“任务语义”，传输层保持自定义 REST；标准 JSON-RPC 作为下一层 facade，随时可加。**

---

## 二、分层：内核 vs facade

```text
外部协议/Card          内部稳定内核                      真实执行
┌──────────────┐      ┌─────────────────────────┐      ┌──────────────┐
│ Agent Card   │─────▶│ Registry / Catalog      │─────▶│ 子 Agent      │
│ (发现)        │      │ capabilityId / schema   │      │ 8402 / 8400  │
└──────────────┘      ├─────────────────────────┤      └──────────────┘
┌──────────────┐      │ Router / Planner        │
│ REST facade  │─────▶│ LLM + 规则 + 多轮补参    │
│ agent-task-v1│      ├─────────────────────────┤
└──────────────┘      │ Task state machine      │
┌──────────────┐      │ 确认/取消/超时/事件      │
│ 未来 JSON-RPC │─────▶│ 审计/人工队列/指标       │
│ facade       │      └─────────────────────────┘
└──────────────┘
```

| 层 | 现在 | 未来 | 是否影响内核 |
|---|---|---|---|
| 发现 | `/.well-known/agent-card.json` + 子 Agent Card | 动态注册、Manifest 同步 | 否 |
| 传输 | 自定义 REST `agent-task-v1` | JSON-RPC facade | 否 |
| 消息模型 | 任务 + input/output + events | message/part/artifact 映射 | 否 |
| 事件 | 浏览器 SSE + 子 Agent 轮询 | 子 Agent SSE / push notification | 否 |
| 身份 | JWT + 内部令牌 + 委托令牌 | OAuth/mTLS/身份映射表 | 否 |
| 状态 | 进程内 + MySQL | Redis/队列/多实例 | 否 |
| 记忆 | 会话摘要 + 全局画像 | 向量长期记忆/RAG | 否 |

**核心思想：**
> 协议是壳，任务是核。壳可以换，核不能跟着协议变。

---

## 三、Agent Card：已经落地的“薄发现层”

### 3.1 主 Agent Card

```http
GET /.well-known/agent-card.json
```

返回（脱敏）：

```json
{
  "agentId": "wuye-main-agent",
  "protocol": "agent-task-v1",
  "a2aCompatibility": "discovery-only",
  "capabilities": ["property.ticket.query", "..."],
  "endpoints": { "chat": "...", "tasks": "...", "tools": "..." },
  "features": { "sse": true, "humanConfirmation": true, "jsonRpc": false }
}
```

### 3.2 子 Agent Card

```http
GET /api/v1/agents/{agentKey}/card
```

返回该子 Agent 的：

- `agentId/version/dialect/envelope`；
- capability 列表（读写、是否需要确认、执行模式）；
- 相对 endpoint；
- `auth.schemes`（InternalToken/DelegationToken）；
- `features`（取消、异步持久化、流式）。

### 3.3 管理端总览

```http
GET /api/v1/admin/a2a/summary
```

给演示页/运维看：

- 主 Agent Card；
- 每个子 Agent 的 Card + 健康；
- `compatibility`：`discovery-only`、`jsonRpc=false`、`pushNotifications=false`。

UI 入口：右上角头像 → **A2A 能力**。

---

## 四、A2A 标准概念映射

| Google A2A | 本项目 | 状态 |
|---|---|---|
| Agent Card | `/.well-known/agent-card.json`、`/api/v1/agents/{key}/card` | 已有（discovery-only） |
| `message/send` | `POST /api/v1/chat/sessions/{sessionNo}/messages` | 已有（REST，SSE） |
| `tasks/get` | `GET /api/v1/tasks/{taskNo}` | 已有 |
| `tasks/cancel` | `POST /api/v1/tasks/{taskNo}/cancel` | 已有 |
| `tasks/resubscribe` | `GET /api/v1/tasks/{taskNo}/events?afterSeq=` | 轮询版 |
| Task/Artifact | `TaskRecord.output` + `events` | 已有，未用标准 parts |
| Push notification | 无 | 未做 |
| JSON-RPC 2.0 | 无 | 未做（保留 facade） |

**面试话术：**
> “我们不是标准 A2A 的线协议实现，但语义是对齐的：任务、确认、取消、事件。Agent Card 已经提供，JSON-RPC 只是传输层 facade，接进来不影响内部任务模型。”

---

## 五、渐进路线

### Phase 1：现在（已完成）

- Canonical 任务模型；
- REST `agent-task-v1`；
- Agent Card 发现；
- 失败自修复（CLARIFY/RETRY/ALTERNATIVE/NO_CAPABILITY）；
- 人工确认、审计、人工队列、指标；
- 会话摘要 + 全局画像。

### Phase 2：标准传输 facade（1–2 周）

- 新增 `/a2a/v1` JSON-RPC transport；
- `message/send`、`tasks/get`、`tasks/cancel`、`tasks/resubscribe`；
- 把 JSON-RPC 请求翻译成内部 `RouteDecision/TaskRecord`；
- 内核不改。

### Phase 3：动态发现（2–4 周）

- Manifest 自动同步；
- `wa_agent_registry` / `wa_agent_capability`；
- 版本兼容校验；
- Agent Card 由注册表动态生成。

### Phase 4：生产化（1–2 月）

- 子 Agent SSE / push notification；
- Redis 确认令牌/限流/分布式锁；
- 后台任务 worker；
- OpenTelemetry/Prometheus；
- 多租户身份映射。

---

## 六、面试怎么讲“大局观”

**Q：为什么不做完整 A2A？**
> “A2A 的完整协议很重，但我的核心价值不是协议字段，而是把不同 Agent 的方言、状态、确认和失败收敛成可治理的任务模型。协议是壳，任务是核。壳可以渐进替换，核不能反复重写。”

**Q：以后接标准 A2A 要改多少？**
> “只加 transport facade：JSON-RPC 请求翻译成内部 `RouteDecision/TaskRecord`。Agent Card 已经有，任务状态机、确认、审计、人工队列都不动。”

**Q：为什么先做 Agent Card，不做 JSON-RPC？**
> “Agent Card 是发现层，成本最低、演示价值最高，也不影响现有 REST。JSON-RPC 是传输层，等有外部 Agent 真正要接时再做，避免过度设计。”

**Q：这套架构的边界？**
> “现在是 hub-and-spoke：主 Agent 统一鉴权、审计、确认和降级，子 Agent 不互相调用。PEER-to-PEER 的 A2A 不是当前目标，因为政务/企业客户更看重可控、可追溯，而不是 Agent 自由协作。”

---

## 七、一句话总结

> **内部稳定内核 + 外部薄 facade + 清晰演进路线。**
> 现在能演示，未来能接标准，不被任何单一协议锁死。
