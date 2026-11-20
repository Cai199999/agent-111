# Property Maintenance Agent 接入使用说明（给主 Agent / Orchestrator）

> 适用对象：未来大项目的主 Agent、Orchestrator 或其他调用方
> 子 Agent 版本：Manifest / Agent Card `0.7.0`
> 协议：`agent-task-v1`
> 本文可**直接复制**到主 Agent 项目的 `docs/integrations/property-maintenance/` 下使用。

---

## 1. 这个子 Agent 是什么

```text
Property Maintenance Agent
  = 物业报修 / 工单 / 派单 / 现场 / 回访 / 知识 / 导出 领域子 Agent
```

它负责：

- 接收领域任务；
- 在自身权限、审计与人工确认边界内执行；
- 返回结构化结果、引用、错误码与人工确认请求；
- 保留自己的数据库、知识库、工具与指标。

它不负责：

- 全局任务规划；
- 跨领域多 Agent 编排；
- 财务、HR、采购等非物业运维领域；
- 主 Agent 的统一身份系统与统一报表中心。

**边界原则：主 Agent 不直连本子 Agent 的 MySQL / MinIO / Milvus，也不共享聊天上下文。**

---

## 2. 部署与启动

### 2.1 推荐形态：独立 Agent Profile

主 Agent 接入时，建议以 `agent` Profile 独立运行，只暴露 Agent 接口：

```bash
export AGENT_SERVICE_PORT=8400
export AGENT_CARD_URL=http://<子Agent地址>:8400/agent/v1
export ENV_FILE=/path/to/agent-gongdan/.env

java -jar agent-gongdan-java-bff.jar --spring.profiles.active=agent
```

| 项 | 值 |
|---|---|
| 默认端口 | `8400`（`AGENT_SERVICE_PORT` 可覆盖） |
| 暴露路径 | `/agent/**`、`/.well-known/agent-card.json`、`/agent-card.json`、`/agent/v1/card`、`/actuator/health` |
| 不暴露 | 用户端 `/api/v1/**`（返回 404） |
| 依赖 | 本子 Agent 自己的 MySQL 库、Redis 前缀、MinIO bucket |
| 健康检查 | `GET /actuator/health` |

> 不要把主 Agent 的数据库、Redis key 空间、MinIO bucket 与本子 Agent 混用。

### 2.2 嵌入 BFF 形态（仅开发/联调）

默认 BFF 端口 `8200`，同时提供用户端 `/api/v1/**` 与 Agent `/agent/v1/**`。
联调可用，但生产接入建议用 2.1 的独立 Profile。

### 2.3 启动前检查

- 端口空闲（`ss -ltn`）；
- `.env` 中数据库、内部令牌、JWT、MinIO 等配置齐全；
- `AGENT_CARD_URL` 改成主 Agent 实际可达的地址；
- 健康检查返回 `{"status":"UP"}`。

---

## 3. 发现与版本

```http
GET /agent/v1/manifest
GET /.well-known/agent-card.json
GET /agent-card.json
GET /agent/v1/card
```

Manifest 关键字段：

```json
{
  "agentId": "property-maintenance",
  "version": "0.7.0",
  "protocol": "agent-task-v1",
  "taskEndpoint": "/agent/v1/tasks",
  "eventStreamEndpoint": "/agent/v1/tasks/{taskId}/events/stream",
  "executionModes": ["SYNC", "ASYNC"],
  "limits": { "maxContextRefs": 50, "maxRefIdLength": 64, "maxInputBytes": 65536 },
  "enforcement": {
    "deadlineAt": { "enforced": true, "mode": "checked-before-execution" },
    "budget": { "enforced": false, "mode": "recorded-only" },
    "runtimeTimeout": { "enforced": false }
  },
  "capabilities": [ ... ]
}
```

主 Agent 应：

1. 启动时拉取并缓存 Manifest；
2. 按 `version` 处理契约变化；
3. 以 Manifest 的 `capabilities` 为准生成路由/工具清单，不要硬编码能力名；
4. 把 `AGENT_CARD_URL` 配置为实际可达地址。

---

## 4. 鉴权

### 4.1 内部调用

```http
X-Internal-Token: <内部令牌>
X-Actor-Id: 10001
X-Actor-Roles: ADMIN
Content-Type: application/json
X-Trace-Id: <可选，调用链追踪>
```

- `X-Internal-Token` 使用子 Agent `.env` 的 `INTERNAL_TOKEN_P2J_CURRENT`；
- `X-Actor-Id` 必须是真实用户 ID；
- `X-Actor-Roles` 逗号分隔；
- 即使携带内部令牌，子 Agent 仍会做票级权限与数据范围校验。

### 4.2 委托令牌（推荐）

主 Agent 代表最终用户调用时，签发短时委托令牌：

```http
POST /agent/v1/delegation/token
Content-Type: application/json

{
  "actorId": 10003,
  "roles": ["REPORTER"],
  "scopes": ["ticket.query", "ticket.create"],
  "ttlSeconds": 600
}
```

后续请求：

```http
X-Internal-Token: <内部令牌>
X-Delegation-Token: <委托令牌>
```

规则：

- 存在委托令牌时，Actor/角色/scope 以令牌声明为准，`X-Actor-*` 被忽略；
- scope 支持 `*` 或能力名 / `ticket.attachment.upload`；
- 默认 10 分钟，最长 1 小时；
- 委托令牌不能再次委托；
- **不要把子 Agent 的内部令牌转发给最终用户或其他 Agent。**

---

## 5. 提交任务

```http
POST /agent/v1/tasks
```

```json
{
  "taskType": "ticket.query",
  "input": { "status": "PENDING", "limit": 5 },
  "clientTaskId": "main-agent-20261119-001",
  "executionMode": "SYNC",
  "parentTaskId": "upstream-task-001",
  "correlationId": "flow-20261119-001",
  "deadlineAt": "2026-11-19T18:00:00+08:00",
  "contextRefs": { "kind": "TICKET", "id": "GD20261119000107" },
  "budget": { "maxTokens": 1000, "maxLatencyMs": 10000 }
}
```

字段说明：

| 字段 | 必填 | 说明 |
|---|---|---|
| `taskType` | 是 | 能力名，见第 9 节 |
| `input` | 否 | 能力参数；序列化后默认 ≤ 65536 字节 |
| `clientTaskId` | 建议 | 幂等键；同一 Actor 重复提交返回同一任务；重试必须复用 |
| `executionMode` | 否 | `SYNC`（默认）/ `ASYNC` |
| `parentTaskId` | 否 | 上游任务 ID，用于调用链 |
| `correlationId` | 建议 | 跨 Agent 关联 ID，贯穿日志/审计 |
| `deadlineAt` | 否 | ISO 8601；提交时拒绝已过期，执行前再次检查 |
| `contextRefs` | 否 | 只传引用，最多 50 项；**禁止传完整聊天历史** |
| `budget` | 否 | 仅记录，不强制中断 |

响应：

```json
{
  "code": 0,
  "data": {
    "taskId": "...",
    "agentId": "property-maintenance",
    "capability": "ticket.query",
    "status": "SUCCEEDED",
    "result": [],
    "errorCode": null,
    "errorMessage": null,
    "traceId": "...",
    "latencyMs": 12,
    "executionMode": "SYNC",
    "parentTaskId": "...",
    "correlationId": "...",
    "deadlineAt": "...",
    "contextRefs": {},
    "budget": {}
  }
}
```

### 5.1 同步 vs 异步

| 模式 | 行为 |
|---|---|
| `SYNC`（默认） | 请求内执行，直接返回 `SUCCEEDED` / `FAILED` |
| `ASYNC` | 只读任务返回 `PENDING`；写任务先 `WAITING_CONFIRM`，确认后 `PENDING`，由 Worker 执行 |

异步状态机：

```text
PENDING → RUNNING → SUCCEEDED / FAILED
PENDING / RUNNING / WAITING_CONFIRM → CANCELLED
```

主 Agent 应轮询 `GET /agent/v1/tasks/{taskId}` 或订阅 SSE，直到终态。

---

## 6. 写能力与人工确认

所有写能力都会返回 `WAITING_CONFIRM`，**未确认不会写库/生成文件**。

```text
POST /agent/v1/tasks
  → status=WAITING_CONFIRM
  → 返回 confirmationToken + 摘要
  → 主 Agent 展示给用户确认
  → POST /agent/v1/tasks/{taskId}/confirm
  → SUCCEEDED / FAILED
```

确认：

```http
POST /agent/v1/tasks/{taskId}/confirm
Content-Type: application/json

{"confirmationToken":"..."}
```

规则：

- 令牌 10 分钟、一次性；
- 只能由同一 Actor 确认；
- 未确认绝不写库；
- 确认后仍走子 Agent 既有工单/导出服务；
- 任务已落库，重启不丢失。

取消：

```http
POST /agent/v1/tasks/{taskId}/cancel
```

仅 `PENDING / RUNNING / WAITING_CONFIRM` 可取消。

> 主 Agent 必须把确认请求原样展示给用户，**不得自动确认，也不得代替用户点击**。

---

## 7. 事件与 SSE

历史事件：

```http
GET /agent/v1/tasks/{taskId}/events
```

实时订阅：

```http
GET /agent/v1/tasks/{taskId}/events/stream
Accept: text/event-stream
```

事件格式：

```text
id:<eventId>
event:task-event
data:{"id":"...","taskId":"...","status":"RUNNING","message":null,"createdAt":"..."}

event:done
data:{"taskId":"...","status":"SUCCEEDED"}
```

说明：

- 先回放历史，再增量推送；
- 终态发送 `done` 并关闭；
- 默认 1s 轮询事件表、15s 心跳、10 分钟超时，均可配置；
- 仅同 Actor 可订阅。

---

## 8. 附件上传

`ticket.resolve` 要求至少一张 `ONSITE` 现场照片：

```http
POST /agent/v1/tickets/{ticketId}/attachments?type=ONSITE
Content-Type: multipart/form-data

file=@onsite.png
```

| type | 用途 | 权限 |
|---|---|---|
| `ONSITE` | 处理人现场照片 | 必须是该工单处理人 |
| `USER` | 报修人图片 | 工单可读 |
| `GENERAL` | 一般附件 | 工单可读 |

使用委托令牌时，scope 需包含 `*` 或 `ticket.attachment.upload`。

---

## 9. 能力清单

| capability | 主要 input | 说明 | 需确认 |
|---|---|---|---|
| `ticket.query` | status、keyword、limit、priorityCode、assigneeId、startTime、endTime、openOnly | 查询当前 Actor 可见工单 | 否 |
| `ticket.detail` | ticketId 或 ticketNo | 工单详情 | 否 |
| `ticket.similar` | query、topK | 相似历史工单 | 否 |
| `knowledge.search` | query、topK、categoryCode | 知识库检索 | 否 |
| `ticket.create` | description、ticketTypeCode、locationType、locationText、buildingId、roomId 等 | 建单 | 是 |
| `ticket.export` | startTime、endTime、status 等 | 导出 Excel | 是 |
| `ticket.assign` | ticketId、assigneeId | 派单 | 是 |
| `ticket.accept` | ticketId | 接单 | 是 |
| `ticket.resolve` | ticketId、serviceItemText、onsiteSituation | 提交解决；需已有 ONSITE 照片 | 是 |
| `ticket.verify` | ticketId、approved | 验证 | 是 |
| `ticket.close` | ticketId、closeReasonCode | 关闭 | 是 |
| `ticket.cancel` | ticketId、cancelReason | 取消 | 是 |
| `ticket.hold` | ticketId、reason | 挂起 | 是 |
| `ticket.resume` | ticketId、reason | 恢复 | 是 |
| `ticket.finish` | ticketId、reason | 结束 | 是 |
| `ticket.comment.add` | ticketId、content、visibility | 评论 | 是 |
| `ticket.material.add` | ticketId、materialName、quantity、unit、amount | 材料 | 是 |
| `ticket.visit.add` | ticketId、visitMethod、visitResult、status | 回访 | 是 |

只读能力立即返回 `SUCCEEDED` / `FAILED`；写能力返回 `WAITING_CONFIRM`。

---

## 10. 错误码与重试

| 场景 | 表现 | 主 Agent 建议 |
|---|---|---|
| 内部令牌无效 | `401` / `10001` | 检查令牌，不重试 |
| 越权 / scope 拒绝 | `403` / `10003` | 不重试，提示用户或换 Actor |
| 任务不存在 | `404` / `20001` | 不重试 |
| 状态冲突 | `409` / `20002` | 刷新任务状态后决定 |
| 超过截止时间 | `FAILED` / `20005` | 重新提交并给新 deadline |
| 依赖不可用 | `50004` | 可重试（同 `clientTaskId`） |
| 参数校验 | `400` / `60001` | 修正参数后重试 |

重试原则：

- 只重试可重试错误；
- 必须复用相同 `clientTaskId`；
- 写操作重试前先查询原任务状态，避免重复确认/重复执行。

---

## 11. 可观测性

聚合端点：

```http
GET /agent/v1/metrics/summary?from=<ISO8601>&to=<ISO8601>
X-Internal-Token: ...
X-Actor-Id: 10001
X-Actor-Roles: ADMIN
```

返回：

- `agentTasks`：总数、活跃数、终态数、成功率、状态/能力分布、P95；
- `chat`：助手消息数、失败数、token、平均/P95 耗时；
- `toolCalls`：工具调用总数、按工具/状态分布。

实时指标（Actuator）：

```http
GET /actuator/metrics
```

| 指标 | 说明 |
|---|---|
| `agent_task_total{capability,status}` | 任务状态流转 |
| `agent_task_latency_seconds{capability}` | 任务耗时 |
| `agent_task_queue_depth` | 异步队列积压 |
| `agent_task_active_threads` | 执行线程数 |
| `agent_tool_calls_total{tool,status}` | 工具调用 |

主 Agent 应使用 `correlationId` 关联自己的链路日志。

---

## 12. 已知限制（主 Agent 必须兜底）

| 限制 | 主 Agent 应对 |
|---|---|
| 异步队列是进程内线程池 | 优先 `SYNC`；或接受任务中断风险 |
| 启动恢复只重投 `PENDING`，超时 `RUNNING` 标记失败 | 不要依赖子 Agent 保证任务不中断 |
| `deadlineAt` 只在执行前检查 | 主 Agent 自己做超时与取消 |
| `budget` 仅记录 | 主 Agent 自己做成本/耗时控制 |
| 任务 token/成本字段当前为 0 | 不要依赖子 Agent 做成本统计 |
| 委托令牌是 HMAC 短令牌 | 不是 OAuth2/OIDC；跨域信任仍需边界控制 |
| 当前 Task API 固定 `tenantId=0` | 多租户主 Agent 接入前需补租户字段 |
| 无完整 A2A 注册中心 / SDK | 按本文直接 HTTP 接入，或自行封装 Adapter |
| SSE 为数据库轮询 | 高实时要求场景需在主 Agent 侧做超时与降级 |

---

## 13. 最小接入步骤（主 Agent 清单）

1. 配置子 Agent 地址与 `AGENT_CARD_URL`；
2. 启动子 Agent（`agent` Profile，8400）并检查 `/actuator/health`；
3. 拉取 `/agent/v1/manifest`，缓存能力清单；
4. 配置内部令牌与委托令牌签发；
5. 实现 Task 提交（优先 `SYNC`）与幂等键；
6. 实现 `WAITING_CONFIRM` 的展示与确认回调；
7. 实现事件轮询或 SSE；
8. 映射错误码与重试策略；
9. 用 `correlationId` 串日志；
10. 接入 `/agent/v1/metrics/summary` 或 `/actuator/metrics`。

### 最小调用示例

```bash
curl -X POST http://127.0.0.1:8400/agent/v1/tasks \
  -H "X-Internal-Token: $INTERNAL_TOKEN_P2J_CURRENT" \
  -H "X-Actor-Id: 10001" \
  -H "X-Actor-Roles: ADMIN" \
  -H "Content-Type: application/json" \
  -d '{
    "taskType": "ticket.detail",
    "input": {"ticketNo": "GD20261119000107"},
    "clientTaskId": "main-agent-demo-001",
    "executionMode": "SYNC"
  }'
```

---

## 14. 契约稳定性

以下内容视为对外契约，变更必须升级 Manifest `version`：

- Manifest / Agent Card 必需字段与 `protocol=agent-task-v1`；
- 18 个能力名称及其 `mode` / `requiresConfirm`；
- Task 请求字段与响应字段集合；
- Manifest `limits` 与 `enforcement`；
- 错误语义（含 `20005` 截止时间）；
- 事件流 `task-event` 与终态 `done`。

主 Agent 应在启动时校验版本；遇到主版本变化时拒绝接入并记录告警。

---

## 15. 接入验收清单

- [ ] `/actuator/health` 返回 UP
- [ ] Manifest 可拉取，`version=0.7.0`，能力数 18
- [ ] Agent Card 地址为实际可达地址
- [ ] 内部令牌缺失/错误返回 401/10001
- [ ] 越权返回 403/10003
- [ ] 只读任务 SYNC 成功
- [ ] 写任务返回 `WAITING_CONFIRM`，未确认不落库
- [ ] 确认后状态为 `SUCCEEDED`，重复确认冲突
- [ ] 异步任务可轮询到终态
- [ ] SSE 先回放历史再推送 `done`
- [ ] `clientTaskId` 重试幂等
- [ ] `correlationId` 能在日志中关联
- [ ] 指标端点可用（ADMIN）
- [ ] 主 Agent 未直连子 Agent 数据库
- [ ] 主 Agent 未转发子 Agent 内部令牌
