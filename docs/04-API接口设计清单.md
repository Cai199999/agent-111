# 04-API接口设计清单

> 项目：`wuye-agent`
> 版本：v0.1（设计稿）
> 日期：2026-11-20
> 基础路径：`/api/v1`（用户端）、`/internal/v1`（内部）
> 鉴权：用户端 `Authorization: Bearer <JWT>`；内部 `X-Internal-Token`
> 编码：UTF-8；时间：ISO 8601（`+08:00`）；金额：字符串或分为单位（首期不涉及）

---

## 1. 通用约定

### 1.1 响应包络

成功：

```json
{
  "code": "OK",
  "message": "ok",
  "data": {},
  "traceId": "trc_20261120_xxx"
}
```

失败：

```json
{
  "code": "TASK_STATE_CONFLICT",
  "message": "任务当前状态不允许确认",
  "data": null,
  "traceId": "trc_20261120_xxx",
  "details": { "currentStatus": "SUCCEEDED" }
}
```

### 1.2 HTTP 状态码

| HTTP | 场景 |
|---:|---|
| 200 | 查询/操作成功 |
| 201 | 创建成功 |
| 202 | 异步任务已受理 |
| 400 | 参数校验失败 |
| 401 | 未登录/令牌无效 |
| 403 | 无权限/无身份映射/写操作受限 |
| 404 | 会话/任务/Agent/能力不存在 |
| 409 | 状态冲突/幂等冲突/重复确认 |
| 410 | 确认令牌过期 |
| 429 | 限流/队列满 |
| 500 | 内部错误 |
| 503 | 子 Agent/LLM 不可用 |

### 1.3 分页

请求：`page`（从 1 开始，默认 1）、`size`（默认 20，最大 100）。

响应：

```json
{ "items": [], "total": 0, "page": 1, "size": 20 }
```

### 1.4 幂等

| 场景 | 键 | 行为 |
|---|---|---|
| 发送消息 | `clientMessageId`（会话内唯一） | 同键同体重放原消息 |
| 任务提交 | `clientTaskId`（Agent 内唯一） | 同键同体返回原任务；异体 409 |
| 确认/取消 | 任务状态机 | 重复操作返回 409 |
| Agent 注册 | `agentKey` | 重复注册 409 |

### 1.5 追踪头

| Header | 方向 | 说明 |
|---|---|---|
| `X-Trace-Id` | 入/出 | 主 Agent 追踪 ID；不传则生成 |
| `X-Correlation-Id` | 入/出 | 跨 Agent 链路；主 Agent 生成并透传 |
| `X-Request-Id` | 入 | 单次 HTTP 请求 ID |

### 1.6 统一错误码

| code | HTTP | 说明 | 可重试 |
|---|---:|---|---|
| `AUTH_UNAUTHORIZED` | 401 | 未登录/令牌无效 | 否 |
| `AUTH_FORBIDDEN` | 403 | 无权限/无身份映射 | 否 |
| `VALIDATION_ERROR` | 400 | 参数错误 | 否 |
| `SESSION_NOT_FOUND` | 404 | 会话不存在 | 否 |
| `TASK_NOT_FOUND` | 404 | 任务不存在 | 否 |
| `TASK_STATE_CONFLICT` | 409 | 状态不允许 | 否 |
| `CONFIRM_EXPIRED` | 410 | 确认令牌过期 | 否 |
| `CONFIRM_REQUIRED` | 409 | 需要先确认 | 否 |
| `AGENT_NOT_FOUND` | 404 | Agent 未注册 | 否 |
| `AGENT_DISABLED` | 403 | Agent 已停用 | 否 |
| `AGENT_DEGRADED` | 503 | Agent 健康检查失败 | 是 |
| `CAPABILITY_NOT_FOUND` | 404 | 能力不存在 | 否 |
| `CAPABILITY_DISABLED` | 403 | 能力未启用 | 否 |
| `ROUTE_AMBIGUOUS` | 409 | 路由歧义，需澄清 | 否 |
| `RATE_LIMITED` | 429 | 限流 | 是（退避） |
| `DOWNSTREAM_UNAVAILABLE` | 503 | 子 Agent/LLM 不可用 | 是 |
| `INTERNAL_ERROR` | 500 | 内部错误 | 否 |

---

## 2. 认证 API

### 2.1 登录

```http
POST /api/v1/auth/login
Content-Type: application/json

{ "username": "admin", "password": "******" }
```

响应：

```json
{
  "code": "OK",
  "data": {
    "accessToken": "eyJ...",
    "expiresIn": 7200,
    "user": { "userNo": "U0001", "username": "admin", "displayName": "管理员", "roles": ["ADMIN"] }
  },
  "traceId": "..."
}
```

### 2.2 当前用户

```http
GET /api/v1/auth/me
Authorization: Bearer <JWT>
```

### 2.3 登出

```http
POST /api/v1/auth/logout
Authorization: Bearer <JWT>
```

> 首期 JWT 无状态；登出由前端清除 + 服务端短黑名单（Redis，TTL 与 JWT 一致）。

---

## 3. 会话与聊天 API

### 3.1 创建会话

```http
POST /api/v1/chat/sessions
Authorization: Bearer <JWT>

{ "title": "新会话" }
```

响应 `201`：

```json
{ "code": "OK", "data": { "sessionNo": "ws_20261120_0001", "title": "新会话", "createdAt": "..." } }
```

### 3.2 会话列表

```http
GET /api/v1/chat/sessions?page=1&size=20
```

### 3.3 会话详情

```http
GET /api/v1/chat/sessions/{sessionNo}
```

### 3.4 消息历史

```http
GET /api/v1/chat/sessions/{sessionNo}/messages?before=<messageNo>&limit=50
```

响应：

```json
{
  "code": "OK",
  "data": {
    "items": [
      {
        "messageNo": "wm_...",
        "role": "USER",
        "content": "我有哪些待处理工单？",
        "contentType": "TEXT",
        "createdAt": "..."
      },
      {
        "messageNo": "wm_...",
        "role": "ASSISTANT",
        "content": "你有 3 张待处理工单……",
        "contentType": "CARD",
        "metadata": {
          "capabilityId": "property.ticket.query",
          "taskNo": "wt_...",
          "cards": []
        },
        "createdAt": "..."
      }
    ],
    "nextBefore": "wm_..."
  }
}
```

### 3.5 发送消息（SSE 流式）

```http
POST /api/v1/chat/sessions/{sessionNo}/messages
Authorization: Bearer <JWT>
Accept: text/event-stream
Content-Type: application/json

{
  "content": "把 GD20261119000107 派给 10002",
  "clientMessageId": "cm_20261120_0001",
  "attachmentIds": []
}
```

> 用 `fetch` + `ReadableStream` 读取；`EventSource` 不支持 POST。

SSE 事件序列示例：

```text
event: meta
data: {"sessionNo":"ws_...","messageNo":"wm_...","traceId":"trc_..."}

event: route
data: {"capabilityId":"property.ticket.assign","confidence":0.96,"router":"RULE","reason":"命中派单+工单号"}

event: task
data: {"taskNo":"wt_...","agentKey":"property-maintenance","capabilityId":"property.ticket.assign","status":"WAITING_CONFIRM","executionMode":"SYNC"}

event: confirm
data: {"taskNo":"wt_...","summary":{"title":"派单确认","riskLevel":"HIGH","fields":[{"label":"工单","value":"GD20261119000107"},{"label":"处理人","value":"10002"}]},"expiresAt":"2026-11-20T10:10:00+08:00"}

event: done
data: {"messageNo":"wm_...","status":"WAITING_CONFIRM"}
```

> **确认令牌不出现在任何事件中**；前端只拿 `taskNo`。

### 3.6 SSE 事件定义

| event | data 关键字段 | 说明 |
|---|---|---|
| `meta` | `sessionNo`、`messageNo`、`traceId` | 本轮开始 |
| `route` | `capabilityId`、`confidence`、`router`、`reason` | 路由结果 |
| `delta` | `content` | 自然语言增量 |
| `task` | `taskNo`、`agentKey`、`capabilityId`、`status`、`executionMode` | 任务卡片 |
| `confirm` | `taskNo`、`summary`、`expiresAt` | 确认卡片（无 token） |
| `task_event` | `taskNo`、`seq`、`type`、`status`、`payload` | 任务事件 |
| `done` | `messageNo`、`status`、`taskNo?` | 本轮结束 |
| `error` | `code`、`message`、`retryable` | 错误 |

### 3.7 删除会话

```http
DELETE /api/v1/chat/sessions/{sessionNo}
Authorization: Bearer <JWT>
```

响应：

```json
{ "code": "OK", "data": { "sessionNo": "ws_...", "deleted": true }, "traceId": "..." }
```

- 硬删除会话及其消息、任务、任务事件、确认令牌；
- 会话不存在返回 `404 SESSION_NOT_FOUND`；
- 不可恢复，前端必须先二次确认；
- 仅会话所属用户可删除（生产化接入 RBAC 后）。

### 3.8 断线重连

```http
GET /api/v1/chat/sessions/{sessionNo}/events?afterSeq=12
```

返回 `text/event-stream`，从 `seq > afterSeq` 补发；无事件时发送心跳 `event: ping`。

---

## 4. 任务 API

### 4.1 任务列表

```http
GET /api/v1/tasks?sessionNo=ws_...&status=WAITING_CONFIRM&page=1&size=20
```

### 4.2 任务详情

```http
GET /api/v1/tasks/{taskNo}
```

响应：

```json
{
  "code": "OK",
  "data": {
    "taskNo": "wt_...",
    "agentKey": "property-maintenance",
    "capabilityId": "property.ticket.assign",
    "status": "WAITING_CONFIRM",
    "executionMode": "SYNC",
    "cancelSupported": true,
    "cancelable": true,
    "progress": null,
    "currentStep": null,
    "cancelReason": null,
    "input": { "ticketId": "...", "assigneeId": 10002 },
    "output": null,
    "error": null,
    "confirmation": {
      "required": true,
      "status": "PENDING",
      "expiresAt": "2026-11-20T10:10:00+08:00",
      "summary": { "title": "派单确认", "riskLevel": "HIGH", "fields": [] }
    },
    "parentTaskId": null,
    "correlationId": "corr-...",
    "traceId": "trc-...",
    "createdAt": "...",
    "startedAt": "...",
    "finishedAt": null,
    "latencyMs": null,
    "progress": null
  },
  "traceId": "..."
}
```

### 4.3 确认

```http
POST /api/v1/tasks/{taskNo}/confirm
Authorization: Bearer <JWT>
Content-Type: application/json

{ "decision": "APPROVE", "comment": "同意" }
```

- `decision`：`APPROVE` / `REJECT`；
- 只有任务发起人能确认；
- 重复确认 → `409 TASK_STATE_CONFLICT`；
- 过期 → `410 CONFIRM_EXPIRED`；
- 主 Agent 从 Redis 取子 Agent 令牌并转发，**绝不回传前端**。

响应：

```json
{ "code": "OK", "data": { "taskNo": "wt_...", "status": "SUCCEEDED | REJECTED" }, "traceId": "..." }
```

### 4.4 取消

```http
POST /api/v1/tasks/{taskNo}/cancel
Content-Type: application/json

{ "reason": "用户取消" }
```

- 仅 `PENDING/RUNNING/WAITING_CONFIRM` 可取消；
- 物业与合同均已支持取消；合同为协作式取消，Worker 在步骤间检查取消信号；
- 终态条件更新，Worker 不会覆盖 `CANCELLED`；
- 取消后释放 Redis 确认令牌；审计记录 `cancelReason`。

### 4.5 任务事件轮询

```http
GET /api/v1/tasks/{taskNo}/events?afterSeq=0
```

响应：

```json
{
  "code": "OK",
  "data": {
    "items": [
      { "seq": 1, "type": "STATUS_CHANGED", "status": "RUNNING", "payload": {}, "createdAt": "..." },
      { "seq": 2, "type": "TOOL_CALL", "status": "RUNNING", "payload": { "tool": "knowledge.search" }, "createdAt": "..." }
    ],
    "nextSeq": 3,
    "terminal": false
  },
  "traceId": "..."
}
```

### 4.6 任务事件 SSE

```http
GET /api/v1/tasks/{taskNo}/events/stream?afterSeq=0
Accept: text/event-stream
```

事件：`task-event` + 终态 `done`。

---

## 5. 附件 API

### 5.1 上传

```http
POST /api/v1/attachments
Authorization: Bearer <JWT>
Content-Type: multipart/form-data

file=@onsite.png
purpose=PROPERTY_ONSITE
```

响应：

```json
{ "code": "OK", "data": { "attachmentNo": "wa_...", "fileName": "onsite.png", "sizeBytes": 102400, "status": "UPLOADED" } }
```

限制：单文件 ≤ 10MB；允许 `png/jpg/jpeg/pdf/docx`；服务端校验魔数。

### 5.2 查询/下载

```http
GET /api/v1/attachments/{attachmentNo}
GET /api/v1/attachments/{attachmentNo}/download
```

- 仅上传人或会话参与者可访问；
- 下载走主 Agent 鉴权，返回 MinIO 预签名或流式代理。

---

## 6. Agent 管理 API（ADMIN）

### 6.1 列表

```http
GET /api/v1/admin/agents
```

### 6.2 注册

```http
POST /api/v1/admin/agents
Content-Type: application/json

{
  "agentKey": "property-maintenance",
  "displayName": "物业运维 Agent",
  "baseUrl": "http://127.0.0.1:8402",
  "dialect": "property-v08",
  "credentialRef": "AGENT_PROPERTY",
  "delegationAudience": "property-maintenance",
  "enabled": true
}
```

> `baseUrl` 必须命中 SSRF 白名单；`credentialRef` 只存前缀，真实凭据在环境变量。

### 6.3 更新/启停

```http
PUT /api/v1/admin/agents/{agentKey}
PATCH /api/v1/admin/agents/{agentKey}/status
{ "status": "ENABLED" }
```

### 6.4 同步 Manifest

```http
POST /api/v1/admin/agents/{agentKey}/sync
```

响应：

```json
{
  "code": "OK",
  "data": {
    "agentKey": "property-maintenance",
    "manifestVersion": "0.8.0",
    "capabilityCount": 18,
    "changed": true,
    "warnings": []
  },
  "traceId": "..."
}
```

### 6.5 能力列表/开关

```http
GET /api/v1/admin/agents/{agentKey}/capabilities
PATCH /api/v1/admin/agents/{agentKey}/capabilities/{capabilityId}
{ "enabled": false }
```

### 6.6 健康检查

```http
POST /api/v1/admin/agents/{agentKey}/health-check
```

响应：

```json
{ "code": "OK", "data": { "agentKey": "property-maintenance", "health": "UP", "ready": "UP", "latencyMs": 8 } }
```

### 6.7 原始 Manifest

```http
GET /api/v1/admin/agents/{agentKey}/manifest
```

---

## 7. 可观测 API（ADMIN）

### 7.1 指标汇总

```http
GET /api/v1/admin/metrics/summary?from=2026-11-20T00:00:00%2B08:00&to=2026-11-21T00:00:00%2B08:00
```

返回：任务总数/成功率/P95、按 Agent/能力分布、确认通过率、路由分布、LLM 耗时。

### 7.2 审计日志

```http
GET /api/v1/admin/audit-logs?userId=&action=&from=&to=&page=1&size=50
```

### 7.3 路由决策

```http
GET /api/v1/admin/routing-decisions?sessionNo=&capabilityId=&from=&to=&page=1&size=50
```

---

## 8. 内部 API（Java ↔ Python）

> 仅内网；`X-Internal-Token`；Python **不持有**子 Agent 凭据，也不调用子 Agent。

### 8.1 路由决策

```http
POST /internal/v1/ai/route
Content-Type: application/json
X-Internal-Token: <j2p token>

{
  "traceId": "trc_...",
  "sessionNo": "ws_...",
  "userMessage": "我有哪些待处理工单？",
  "recentTurns": [
    { "role": "USER", "content": "..." },
    { "role": "ASSISTANT", "content": "..." }
  ],
  "capabilities": [
    {
      "capabilityId": "property.ticket.query",
      "displayName": "查询工单",
      "description": "查询当前 Actor 可见工单",
      "readOnly": true,
      "requiresConfirmation": false,
      "inputSchema": {}
    }
  ],
  "currentDomain": "property"
}
```

响应：

```json
{
  "code": "OK",
  "data": {
    "intent": "ticket.query",
    "capabilityId": "property.ticket.query",
    "args": { "status": "PENDING", "limit": 5 },
    "confidence": 0.93,
    "needClarification": false,
    "clarification": null,
    "reason": "用户询问待处理工单"
  }
}
```

### 8.2 槽位补全

```http
POST /internal/v1/ai/fill-slots
```

请求：`capabilityId`、`inputSchema`、`userMessage`、`recentTurns`、`currentArgs`。

响应：`args`、`missingFields`、`questions`。

### 8.3 结果生成

```http
POST /internal/v1/ai/compose
```

请求：`userMessage`、`capabilityId`、`taskResult`（已裁剪）、`citations`。

响应：`answer`、`followUps`、`citations`。

### 8.4 健康

```http
GET /internal/v1/ai/health
```

---

## 9. 出站子 Agent 调用映射

> 由 Java `AgentAdapter` 执行；以下为 Adapter 内部行为，不暴露给浏览器。

| 动作 | 物业 Agent（property-v08） | 合同 Agent（contract-v07） |
|---|---|---|
| Manifest | `GET /agent/v1/manifest` | `GET /agent/v1/manifest` |
| 提交任务 | `POST /agent/v1/tasks` | `POST /agent/v1/tasks` |
| 查询任务 | `GET /agent/v1/tasks/{taskId}` | `GET /agent/v1/tasks/{taskId}` |
| 确认 | `POST /agent/v1/tasks/{taskId}/confirm` | 同左，body 带 `decision/comment` |
| 取消 | `POST /agent/v1/tasks/{taskId}/cancel` | `POST /agent/v1/tasks/{taskId}/cancel`（协作式） |
| 事件 | `GET /agent/v1/tasks/{taskId}/events` | 同左，支持 `afterSeq` |
| SSE | `GET /agent/v1/tasks/{taskId}/events/stream` | 同左 |
| 健康 | `GET /agent/v1/health`、`GET /agent/v1/ready` | `GET /health`、`GET /ready` |
| 委托令牌 | `POST /agent/v1/delegation/token` | `POST /agent/v1/delegation/token`（`audience=agent-hetong`） |
| 合同解析 | - | `contract.resolve`（需委托令牌） |
| 合同导入 | - | `contract.import`（Manifest `enabled=false`） |
| 附件上传 | `POST /agent/v1/tickets/{ticketId}/attachments?type=ONSITE` | 首期无 |
| 附件列表 | `GET /agent/v1/tickets/{ticketId}/attachments` | 首期无 |

> 合同异步提交返回 `202`、同步返回 `200`；Adapter 必须接受两者，并以响应体 `status` 为准，不能按 HTTP 码推断状态。
| 响应包络 | `{code,data}` | 裸对象 |
| 错误码 | 数字（10001/10003/20005…） | 字符串（AUTH_* / TASK_STATE_CONFLICT…） |
| 状态字段 | `data.status` | `status` |
| 任务 ID | `data.taskId` | `taskId` |

请求头：

```http
X-Internal-Token: <该 Agent 的内部令牌>
X-Actor-Id: <映射后的 actorId>
X-Actor-Roles: <映射后的角色>
X-Delegation-Token: <按需签发>
X-Trace-Id: <主 Agent traceId>
X-Correlation-Id: <主 Agent correlationId>
Content-Type: application/json
```

---

## 10. 状态/事件/错误映射

### 10.1 状态

| 子 Agent 原始 | 统一状态 |
|---|---|
| `PENDING` | `PENDING` |
| `RUNNING` | `RUNNING` |
| `WAITING_CONFIRM` | `WAITING_CONFIRM` |
| `SUCCEEDED` | `SUCCEEDED` |
| `FAILED` | `FAILED` |
| `REJECTED`（物业） | `REJECTED` |
| `FAILED` + `CONFIRM_REJECTED`（合同） | `REJECTED` |
| `CANCELLED` | `CANCELLED` |

### 10.2 事件

| 子 Agent 原始 | 统一事件 |
|---|---|
| 物业 `status` 变化 | `STATUS_CHANGED` |
| 合同 `STATUS_CHANGED` | `STATUS_CHANGED` |
| 合同 `PROGRESS` | `PROGRESS` |
| 合同 `TOOL_CALL` | `TOOL_CALL` |
| `ERROR` | `ERROR` |
| 终态 / `done` | `DONE` |

### 10.3 错误示例

| 场景 | 物业原始 | 合同原始 | 统一 |
|---|---|---|---|
| 内部令牌无效 | 401/10001 | 401/`AUTH_INVALID_TOKEN` | `AUTH_UNAUTHORIZED` |
| 越权 | 403/10003 | 403/`AUTH_FORBIDDEN` | `AUTH_FORBIDDEN` |
| 参数错误 | 400/60001 | 400/`INPUT_INVALID` | `VALIDATION_ERROR` |
| 任务不存在 | 404/20001 | 404/`TASK_NOT_FOUND` | `TASK_NOT_FOUND` |
| 状态冲突 | 409/20002 | 409/`TASK_STATE_CONFLICT` | `TASK_STATE_CONFLICT` |
| 截止超时 | FAILED/20005 | - | `FAILED` + `TIMEOUT` |
| 依赖不可用 | 50004 | 503/`DOWNSTREAM_UNAVAILABLE` | `DOWNSTREAM_UNAVAILABLE` |
| 限流 | - | 429/`RATE_LIMITED` | `RATE_LIMITED` |
| 队列满 | - | 429/`QUEUE_FULL` | `RATE_LIMITED` |

---

## 11. 接口验收清单

- [ ] 登录/鉴权/401/403 路径正确；
- [ ] 会话创建、列表、历史可用；
- [ ] 删除会话后，消息、任务、事件均查不到（404）；
- [ ] 发消息 SSE 依次收到 `meta → route → task → ... → done`；
- [ ] `knowledge.search` 歧义返回 `ROUTE_AMBIGUOUS` 或追问事件；
- [ ] 写任务返回 `WAITING_CONFIRM`，事件中不含确认令牌；
- [ ] 确认同意后任务 `SUCCEEDED`；拒绝后统一为 `REJECTED`；
- [ ] 合同异步提交 `202`、同步 `200` 都能正确识别；
- [ ] 合同委托令牌带 `audience`；旧令牌返回 401 时重新签发一次；
- [ ] 物业/合同取消都能进入 `CANCELLED`，Worker 不覆盖；
- [ ] `contract.resolve` 能把合同号/名称解析为 `contractId`，再发起 `contract.review`；
- [ ] 重复确认返回 409；过期返回 410；
- [ ] 任务事件轮询 `afterSeq` 正确；SSE 断线可补发；
- [ ] 附件上传类型/大小/魔数校验；
- [ ] Agent 注册、Manifest 同步、能力开关、健康检查可用；
- [ ] 审计日志可按用户/任务/trace 查询；
- [ ] 子 Agent 停机返回 `AGENT_DEGRADED`/`DOWNSTREAM_UNAVAILABLE`，不 500；
- [ ] 浏览器请求中无子 Agent 令牌/确认令牌/子 Agent 地址。
