# 06-Agent接入与扩展规范

> 项目：`wuye-agent`
> 版本：v0.1（设计稿）
> 日期：2026-11-20
> 目的：定义主 Agent 的 Agent 层、Canonical Agent Contract v1、方言适配、接入 SOP 与扩展策略。
> 关联：`02-技术选型与架构设计.md`、`04-API接口设计清单.md`、`05-最终设计方案与开发规范.md`
>
> **实现状态**：本文是设计稿。动态注册、Manifest 自动同步/版本校验、`streamEvents`、附件上传、身份映射表等为设计目标，未全部落地；当前实现范围与差距见 `docs/demo/平台能力评估.md`。

---

## 1. 分层模型

主 Agent 的 Agent 层分为五块，**新增 Agent 只碰注册/适配，不改核心**：

```text
┌─────────────────────────────────────────────────────────────┐
│ 1. Registry（注册）                                          │
│    agentKey / baseUrl / 凭据引用 / 方言 / 启停 / 健康状态     │
├─────────────────────────────────────────────────────────────┤
│ 2. Discovery（发现）                                         │
│    Manifest 拉取 / 版本校验 / 快照 / 能力同步                 │
├─────────────────────────────────────────────────────────────┤
│ 3. Catalog（能力目录）                                       │
│    capabilityId 命名空间 / JSON Schema / 读写/确认标志        │
├─────────────────────────────────────────────────────────────┤
│ 4. Adapter（协议适配，SPI）                                  │
│    提交 / 查询 / 确认 / 取消 / 事件 / 上传                    │
├─────────────────────────────────────────────────────────────┤
│ 5. Runtime（运行时）                                         │
│    任务状态机 / 确认 / 事件 / 超时 / 审计 / 降级              │
└─────────────────────────────────────────────────────────────┘
```

| 层 | 是否复用 | 新增 Agent 时 |
|---|---|---|
| Registry | 复用 | 新增一条注册记录 |
| Discovery | 复用 | 配置 Manifest 地址 |
| Catalog | 复用 | 自动同步 |
| Adapter | **按需** | 标准协议零代码；方言写一个类 |
| Runtime | 复用 | 不改 |

---

## 2. Canonical Agent Contract v1

新 Agent 建议直接实现本契约；已有 Agent 通过 Adapter 适配。

### 2.1 Manifest

```http
GET /agent/v1/manifest
GET /.well-known/agent-card.json
```

```json
{
  "agentId": "future-agent",
  "displayName": "未来领域 Agent",
  "version": "1.0.0",
  "contractVersion": "1.0.0",
  "protocol": "agent-task-v1",
  "envelope": "raw",
  "endpoints": {
    "manifest": "/agent/v1/manifest",
    "tasks": "/agent/v1/tasks",
    "task": "/agent/v1/tasks/{taskId}",
    "confirm": "/agent/v1/tasks/{taskId}/confirm",
    "cancel": "/agent/v1/tasks/{taskId}/cancel",
    "events": "/agent/v1/tasks/{taskId}/events",
    "eventsStream": "/agent/v1/tasks/{taskId}/events/stream",
    "delegation": "/agent/v1/delegation/token",
    "metrics": "/agent/v1/metrics/summary",
    "health": "/health",
    "ready": "/ready"
  },
  "auth": {
    "schemes": ["InternalToken", "DelegationToken"],
    "headers": ["X-Internal-Token", "X-Actor-Id", "X-Actor-Roles", "X-Delegation-Token"],
    "delegation": {
      "issueEndpoint": "/agent/v1/delegation/token",
      "audience": "future-agent",
      "ttlMaxSeconds": 3600
    }
  },
  "capabilities": [
    {
      "capabilityId": "future.item.query",
      "taskType": "item.query",
      "namespace": "future",
      "displayName": "查询条目",
      "description": "按条件查询条目",
      "readOnly": true,
      "derivedWrites": false,
      "requiresConfirmation": false,
      "executionModes": ["SYNC", "ASYNC"],
      "scopes": ["item:read"],
      "inputSchema": { "type": "object", "properties": {} },
      "outputSchema": { "type": "object" },
      "examples": []
    }
  ],
  "limits": {
    "maxInputBytes": 65536,
    "maxContextRefs": 50,
    "maxConcurrentTasksPerTenant": 4,
    "defaultTimeoutSeconds": 60,
    "maxTimeoutSeconds": 600,
    "rateLimitPerMinute": 120
  },
  "enforcement": {
    "deadlineAt": { "enforced": true, "mode": "checked-before-execution" },
    "budget": { "enforced": false, "mode": "recorded-only" },
    "timeout": { "enforced": true }
  },
  "features": {
    "cancel": true,
    "cancelSupported": true,
    "progress": true,
    "attachments": true,
    "idempotencyLookup": true,
    "asyncDurability": "persistent"
  }
}
```

### 2.2 鉴权

```http
X-Internal-Token: <内部令牌>
X-Actor-Id: <真实 Actor>
X-Actor-Roles: ADMIN,OPERATOR
X-Delegation-Token: <可选，短时委托>
X-Tenant-Id: <可选>
X-Trace-Id: <可选>
X-Correlation-Id: <可选>
```

委托令牌签发：

```http
POST /agent/v1/delegation/token

{
  "actorId": 10003,
  "roles": ["REPORTER"],
  "scopes": ["ticket.query", "ticket.create"],
  "ttlSeconds": 600,
  "audience": "future-agent",
  "tenantId": "0"
}
```

规则：默认 10 分钟、最长 1 小时、一次性/不可再委托、scope 支持 `*` 或能力名；主 Agent 不得把子 Agent 内部令牌转发给用户或其他 Agent。

### 2.3 提交任务

```http
POST /agent/v1/tasks

{
  "taskType": "ticket.query",
  "input": { "status": "PENDING", "limit": 5 },
  "clientTaskId": "wuye-20261120-0001",
  "executionMode": "SYNC",
  "parentTaskId": "wt_...",
  "correlationId": "corr-...",
  "deadlineAt": "2026-11-20T18:00:00+08:00",
  "contextRefs": [
    { "kind": "TICKET", "id": "GD20261119000107" }
  ],
  "budget": { "maxTokens": 1000, "maxLatencyMs": 10000 }
}
```

响应（Canonical v1，推荐裸对象 + HTTP 状态码）：

```json
{
  "taskId": "tsk_...",
  "agentId": "future-agent",
  "capabilityId": "future.item.query",
  "taskType": "item.query",
  "status": "SUCCEEDED",
  "executionMode": "SYNC",
  "output": {},
  "error": null,
  "clientTaskId": "wuye-20261120-0001",
  "parentTaskId": "wt_...",
  "correlationId": "corr-...",
  "traceId": "trc_...",
  "createdAt": "...",
  "startedAt": "...",
  "finishedAt": "...",
  "latencyMs": 12,
  "usage": { "tokens": 0, "costCny": 0 },
  "progress": 100
}
```

### 2.4 确认

```http
POST /agent/v1/tasks/{taskId}/confirm

{ "confirmationToken": "cfm_...", "decision": "APPROVE", "comment": "同意" }
```

- `APPROVE` → 执行；
- `REJECT` → 终态 `REJECTED`（或 `FAILED` + `error.category=USER_REJECTED`，由 Adapter 归一化）；
- 令牌一次性、TTL 10 分钟、绑定任务/Actor/能力/租户；
- 过期 → `FAILED(CONFIRM_TIMEOUT)`；
- 重复 → `409 TASK_STATE_CONFLICT`。

### 2.5 事件

```http
GET /agent/v1/tasks/{taskId}/events?afterSeq=0
GET /agent/v1/tasks/{taskId}/events/stream
Accept: text/event-stream
```

统一事件：

```json
{
  "eventId": "evt_...",
  "seq": 12,
  "taskId": "tsk_...",
  "type": "STATUS_CHANGED",
  "status": "RUNNING",
  "payload": {},
  "createdAt": "..."
}
```

类型：`STATUS_CHANGED / PROGRESS / TOOL_CALL / ERROR / DONE`；SSE 支持 `Last-Event-ID`；终态发 `done` 后关闭。

### 2.6 错误

```json
{
  "httpStatus": 409,
  "code": "TASK_STATE_CONFLICT",
  "legacyCode": null,
  "category": "CONFLICT",
  "retryable": false,
  "message": "任务当前状态不允许确认",
  "details": { "currentStatus": "SUCCEEDED" }
}
```

### 2.7 健康/就绪

```http
GET /health   → {"status":"UP"}
GET /ready    → {"status":"UP","checks":{"db":"UP","llm":"UP","storage":"UP"}}
```

### 2.8 已上线版本与破坏性变更（2026-11-20）

| Agent | 版本 | 破坏性/关键变更 | 主 Agent 处理 |
|---|---|---|---|
| `property-maintenance` | `0.11.0` | `AGENT_SERVICE_PORT` 必填；`REJECTED` 终态；健康路径 `/agent/v1/health`、`/agent/v1/ready`；事件 `seq/type`；任务级 Skills 与 `skillId`；`requiresConfirmation` 确认语义 | 配置 8402；Manifest 同步；Skill 优先选择 |
| `contract-review` | `0.7.0` | 旧委托令牌无 `audience` 返回 401；异步提交 `202`；`CANCELLED` 正式终态 | 按 Agent 重签委托；TaskGateway 接受 200/202；终态集合加 `CANCELLED` |

> 主 Agent 不应依赖旧令牌缓存；升级后应清理 `wuye_agent:delegation:*` 并重新签发。

---

## 3. 方言适配

### 3.1 AgentAdapter SPI

```java
public interface AgentAdapter {
    String dialect();                                   // property-v08 / contract-v07 / canonical-v1
    AgentDescriptor discover();                         // 拉取并解析 Manifest
    HealthStatus health();                              // 健康/就绪
    TaskResult submit(TaskRequest request);             // 提交任务
    TaskResult getTask(String childTaskId);             // 查询任务
    TaskResult confirm(String childTaskId, String token, Decision decision, String comment);
    TaskResult cancel(String childTaskId, String reason);
    List<TaskEvent> events(String childTaskId, long afterSeq);
    void streamEvents(String childTaskId, long afterSeq, Consumer<TaskEvent> consumer);
    Optional<AttachmentRef> uploadAttachment(String ticketId, AttachmentType type, Resource file);
    NormalizedStatus normalizeStatus(String rawStatus);
    AgentError normalizeError(int httpStatus, Object body);
}
```

### 3.2 已接入方言

| 方言 | Agent | 包络 | 错误码 | 健康路径 | 确认体 | 取消 | 异步 |
|---|---|---|---|---|---|---|---|
| `property-v08` | property-maintenance | `{code,data}` | 数字 | `/agent/v1/health`、`/agent/v1/ready` | token+decision | 支持 | `db-recovery` |
| `contract-v07` | contract-review | 裸对象 | 字符串 | `/health`、`/ready` | token+decision | 支持（协作式） | 启动恢复；异步 `202` |
| `canonical-v1` | 未来 Agent | 裸对象 | 字符串 | `/health`、`/ready` | token+decision | 支持 | `persistent` |

### 3.3 适配要点

| 差异 | 物业 Adapter | 合同 Adapter |
|---|---|---|
| 响应取值 | `data.taskId/status` | `taskId/status` |
| 错误 | 数字码映射 | 字符串码映射 |
| 事件 | 由 status 事件合成 `seq/type` | 原生 `seq/eventType/payload` |
| 确认 | 传 token+decision；`REJECT` → `REJECTED` | 传 token+decision；`CONFIRM_REJECTED` → `REJECTED` |
| 取消 | 原生支持，带 `cancelReason` | 协作式取消，终态 `CANCELLED` |
| 委托 | 调签发端点 | 调签发端点（`audience=agent-hetong`）；旧令牌 401 |
| 异步 | `db-recovery`；重启可恢复 | 启动恢复；异步提交 `202` |
| 附件 | 支持 ONSITE 上传/列表 | 首期不支持 |

---

## 4. 新 Agent 接入 SOP

### 4.1 标准 Agent（Canonical v1）

1. **对齐契约**：Manifest、Task、Confirm、Events、Errors、Health/Ready。
2. **注册**：
   ```http
   POST /api/v1/admin/agents
   {
     "agentKey": "future-agent",
     "baseUrl": "http://127.0.0.1:8800",
     "dialect": "canonical-v1",
     "credentialRef": "AGENT_FUTURE"
   }
   ```
3. **配置凭据**：`.env` 增加 `AGENT_FUTURE_INTERNAL_TOKEN` / `AGENT_FUTURE_DELEGATION_SECRET`。
4. **同步 Manifest**：`POST /api/v1/admin/agents/future-agent/sync`。
5. **启用能力**：只读优先，写能力灰度。
6. **身份映射**：`wa_identity_mapping` 配置 Actor/Roles/Scopes。
7. **契约测试**：跑第 6 节清单。
8. **健康检查**：确认 `UP` 后上线。

> 标准 Agent 不需要改 Java 核心代码。

### 4.2 方言 Agent

在 4.1 基础上：

1. 实现 `AgentAdapter`；
2. 注册为 Spring Bean；
3. 在 `AdapterRegistry` 按 `dialect` 路由；
4. 用 Mock/WireMock 覆盖方言响应；
5. 其余流程复用。

### 4.3 不允许的做法

- 在业务 Service 里直接 `WebClient` 调子 Agent；
- 把子 Agent 地址/令牌写进前端；
- 让 Python 直连子 Agent；
- 共享子 Agent 的数据库/Redis/bucket；
- 自动确认写操作。

---

## 5. 扩展性设计

### 5.1 配置化注册

`wa_agent_registry` + `wa_agent_capability` 支持热注册：

- 新增 Agent 只插数据；
- Manifest 同步自动更新能力；
- 能力可单独启停；
- 健康状态影响路由。

### 5.2 能力目录生成

```text
Manifest.capabilities
  → 加 namespace / capabilityId
  → 校验 JSON Schema
  → 写入 wa_agent_capability
  → 供路由/LLM 工具清单/前端使用
```

### 5.3 路由隔离

- 只把 `enabled=1` 且 Agent `ENABLED` 的能力放进路由候选；
- `DEGRADED` Agent 默认降权，连续失败可临时排除；
- 写能力需 `requiresConfirmation` + 身份映射；
- 低置信度必须追问。

---

## 6. 契约测试清单

每个 Agent 接入前必须通过：

- [ ] `GET /agent/v1/manifest` 可拉取，字段齐全，`protocol=agent-task-v1`；
- [ ] Manifest 版本与主 Agent 兼容；
- [ ] 能力数与文档一致，`capabilityId` 唯一；
- [ ] `inputSchema/outputSchema` 可解析；
- [ ] 无令牌 401；错误令牌 401；越权 403；
- [ ] 只读 SYNC 任务成功；
- [ ] 写任务返回 `WAITING_CONFIRM`，未确认不落库；
- [ ] 确认同意 `SUCCEEDED`；拒绝统一为 `REJECTED`；重复 409；过期 410；
- [ ] 合同异步提交 `202`，同步 `200`；Adapter 不按 HTTP 码推断状态；
- [ ] 合同旧委托令牌无 `audience` 返回 401；重签一次可恢复；
- [ ] 物业/合同取消进入 `CANCELLED`；取消竞态下 Worker 不覆盖；
- [ ] `contract.resolve` 可把合同号/名称解析为 `contractId`；`contract.import` 返回禁用；
- [ ] 事件轮询 `afterSeq` 正确；SSE 回放 + `done`；
- [ ] `clientTaskId` 幂等：同键同体原任务；异体 409；
- [ ] `GET /tasks?clientTaskId=` 可恢复（若声明支持）；
- [ ] 健康/就绪可用；`ready` 失败不误判为进程死亡；
- [ ] 429 带 `Retry-After`；
- [ ] 错误对象含 `category/retryable`；
- [ ] 主 Agent 未直连其数据库/对象存储；
- [ ] 内部令牌未出现在任何响应/日志。

---

## 7. 版本与兼容策略

| 变更 | 版本 | 规则 |
|---|---|---|
| 新增可选字段/能力 | minor | 向后兼容；主 Agent 忽略未知字段 |
| 修改语义/删除字段 | major | 必须升级 `contractVersion`；主 Agent 拒绝不兼容版本 |
| 新增能力 | minor | Manifest 自动发现；默认不启用，需管理员勾选 |
| 方言适配 | 无 | Adapter 内部处理，不改契约 |

主 Agent 启动时校验：

```text
protocol == "agent-task-v1"
contractVersion 主版本匹配
必需 endpoints 存在
capabilities[].inputSchema 可解析
```

不兼容 → `status=DISABLED` + 告警，不参与路由。

---

## 8. 自定义 Agent 的边界

**首期不做。** 若未来要做，必须是“受控模板”，不是任意代码：

| 允许 | 禁止 |
|---|---|
| 选择模板、配置提示词 | 上传/执行任意代码 |
| 能力白名单 | 任意 URL/任意工具 |
| 知识库绑定 | 直连他人数据库 |
| 配额/审批/可见范围 | 绕过确认直接写业务数据 |
| 版本化与评测后发布 | 未评测直接上线 |

自定义 Agent 与内置 Agent 共用 Registry/Catalog/Adapter/Runtime，不另起一套。

---

## 9. FAQ

**Q：为什么不让 Python 直接调子 Agent？**
A：凭据集中、审计集中、SSRF 收敛；Python 只做推理，泄露面更小。

**Q：为什么不用统一 SDK 而用 Adapter？**
A：两个现有 Agent 方言不同，短期无法统一；Adapter 是最小改动。新 Agent 走 Canonical v1 后可零代码。

**Q：`knowledge.search` 冲突怎么办？**
A：`capabilityId` 命名空间化：`property.knowledge.search` / `contract.knowledge.search`；`taskType` 保持原样，不破坏子 Agent 契约。

**Q：确认令牌能不能放前端？**
A：不能。浏览器只拿 `taskNo`；令牌 Redis TTL 存储，主 Agent 代转发。

**Q：物业异步队列不可靠怎么办？**
A：主 Agent 自己做 deadline/超时/重试；Manifest 声明 `asyncDurability=process` 时，前端提示“任务可能中断”，并优先 SYNC。

**Q：新增 Agent 要改数据库吗？**
A：标准 Agent 不用；只插注册/能力数据。方言 Agent 只加 Adapter 类。
