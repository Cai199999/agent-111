# 08 主 Agent 演进 · 事件契约与编排骨架

> 版本：v0.1 / 2026-11-26
> 范围：第一阶段「可回滚的第一刀」：冻结事件契约、事件出口/消费者接口、飞轮样本出口、LangGraph 编排骨架。
> 不包含：Kafka / Neo4j / 向量库 / 训练栈 / 多实例分布式消费。

---

## 0. 背景与边界

主 Agent 下一阶段要从「单对话框 + 同步调用」演进为「可编排、可观测、可沉淀」的底座：

```text
用户 → 主 Agent（路由/计划/验收）
         ├─ 事件出口 → 消费者（通知/飞轮/未来的 Kafka）
         ├─ 子 Agent（物业/合同/OA/GIS）→ 结构化结果
         └─ 飞轮样本（输入/输出/错误/耗时）→ 离线评测与迭代
```

本阶段只落地**进程内**能力，不引入新中间件：

- 事件先冻结契约，实现可替换的 `EventPublisher` / `EventConsumer`；
- 飞轮先落 JSONL 文件，不接训练、不接特征平台；
- LangGraph 只做「route → plan → dispatch → collect → verify」骨架，`PLAN_ONLY` 由 Java 执行；
- Python 仍然**不持有、不调用子 Agent**，编排与执行职责不混。

---

## 1. 事件信封 v1（冻结）

```json
{
  "eventId": "evt_1795654037939_6",
  "eventType": "agent.task.completed",
  "eventVersion": 1,
  "occurredAt": "2026-11-26T00:47:17.939Z",
  "source": "wuye-agent-bff",
  "traceId": "trc_...",
  "actorId": "10001",
  "tenantId": "0",
  "subject": "wt_1795654037926_1",
  "idempotencyKey": "agent.task.completed:wt_1795654037926_1",
  "payload": {}
}
```

| 字段 | 约束 |
|---|---|
| `eventId` | 全局唯一；消费端幂等去重第一选择 |
| `eventType` | `<域>.<聚合>.<动作>`，小写点分，见下表 |
| `eventVersion` | v1 冻结；破坏性变更必须升级版本并新增事件类型 |
| `occurredAt` | 事件发生时间（UTC Instant） |
| `source` | 生产方标识，默认 `wuye-agent-bff` |
| `traceId` | 链路 ID，可空 |
| `actorId` / `tenantId` | 发起人与租户，用于隔离与审计 |
| `subject` | 聚合根（任务号/单据号）；未来 Kafka 分区键 |
| `idempotencyKey` | 业务幂等键，默认 `eventType:subject` |
| `payload` | 事件私有载荷；禁止令牌、密钥、密码、完整附件内容 |

### 1.1 投递语义

- **至少一次**（at-least-once）；消费端按 `eventId` / `idempotencyKey` 去重；
- **单机有序**：同一 `subject` 的事件按产生顺序进入进程内总线；
- **故障隔离**：任一消费者抛异常只计数，不影响任务主流程与其他消费者；
- **不阻断业务**：事件通道故障绝不能导致任务提交/确认失败；
- **多实例**：后续 Kafka 按 `subject` 分区保证同任务有序，消费端幂等表兜底；
- **载荷体积**：建议单事件 < 64KB；附件正文等大对象后续只传引用（attachmentId），不直接塞事件。

### 1.2 事件类型（v1）

| eventType | 触发时机 | 关键载荷 |
|---|---|---|
| `agent.task.requested` | 任务受理、调用子 Agent 前 | `taskNo/sessionNo/agentKey/capabilityId/executionMode/input` |
| `agent.task.confirmation.requested` | 进入人工确认 | `taskNo/title/riskLevel/expiresAt/confirmationStatus`（**无 token**） |
| `agent.task.completed` | 成功终态 | `status/durationMs/input/output` |
| `agent.task.failed` | 失败/拒绝终态 | `status/durationMs/input/error{code,category,retryable,message}` |
| `agent.task.cancelled` | 取消终态 | `status/durationMs/input/reason` |
| `agent.flywheel.sample.recorded` | 预留：样本进入外部存储后发出 | `sampleId/taskNo/status` |

> 子 Agent 侧事件（`oa.request.submitted`、`oa.request.decided`、`knowledge.card.created`）
> 由子 Agent 的 Outbox 产生，主 Agent 后续通过消费者接入；本阶段不改子 Agent。

---

## 2. Java 事件出口

```text
event/
  EventEnvelope.java         # 信封 record
  EventTypes.java            # v1 事件类型常量
  EventPublisher.java        # 业务只依赖它；noop() 兜底
  EventConsumer.java         # 消费者接口（幂等）
  EventBus.java              # 发布 + 订阅 + recent/stats
  InMemoryEventBus.java      # 有界环形缓冲 + 同步扇出 + 故障隔离
  EventFactory.java          # 统一生成 eventId/版本/来源/幂等键
```

接入点：`TaskService` 只在**状态迁移**处发事件，轮询刷新不会重复发终态：

- `submit`：`requested`；schema 校验失败直接 `failed`；
- `apply` 状态变为 `WAITING_CONFIRM`：`confirmation.requested`；
- `apply` 状态变为终态：`completed` / `failed` / `cancelled`；
- 确认令牌过期：`failed`。

管理端排障：

```http
GET /api/v1/admin/events/recent?limit=50     # ADMIN
```

返回最近事件与总线指标（`published/delivered/failed/consumers`）。

---

## 3. 飞轮样本出口

`FlywheelSampleService` 是事件总线的第一个真实消费者：

| 字段 | 来源 |
|---|---|
| `sampleId` / `eventId` / `eventType` | 事件 |
| `taskNo/sessionNo/agentKey/capabilityId` | 事件载荷 |
| `actorId/tenantId/status` | 事件信封/载荷 |
| `input/output/error` | 终态载荷 |
| `durationMs` | 任务耗时 |
| `occurredAt` | 终态时间 |

- 只消费 `completed/failed/cancelled`，其余事件忽略；
- 有界幂等集合（2000）防重复投递；
- 落盘 `data/flywheel/samples.jsonl`（已 gitignore），写盘失败只告警；
- 查询：`GET /api/v1/admin/flywheel/samples?limit=20`（ADMIN）。

> 飞轮第一阶段只做**数据出口**：不训练、不回流 prompt、不做自动标注。

---

## 4. LangGraph 编排骨架

位置：`apps/python-ai/app/orchestrator/`，接口：

```http
POST /internal/v1/ai/orchestrate
X-Internal-Token: <INTERNAL_TOKEN_J2P>
```

```json
{
  "traceId": "trc-...",
  "sessionNo": "ws-...",
  "userMessage": "帮我审查这份物业服务合同",
  "actorId": "10001",
  "tenantId": "0",
  "capabilities": [
    {"capabilityId": "contract.review", "agentKey": "contract-review", "displayName": "合同审查"},
    {"capabilityId": "property.ticket.query", "agentKey": "property-maintenance", "displayName": "工单查询"}
  ],
  "plan": [],
  "dispatchMode": "PLAN_ONLY"
}
```

图结构：

```text
START → route ──(歧义/无能力)──→ clarify → END
           └──(可路由)──→ plan ──(无有效步骤)──→ verify
                              └──(有步骤)──→ dispatch → collect → verify → END
```

| 节点 | 职责 | 第一阶段实现 |
|---|---|---|
| `route` | 选定能力 | 领域关键词 + 显示名重合的确定性打分；跨域同分不替用户选择 |
| `plan` | 生成/校验步骤 | 优先使用上层 `plan`；未知/未授权能力丢弃并记录 |
| `dispatch` | 派发 | `PLAN_ONLY` 返回 `DISPATCH_REQUIRED`，Java 执行；`MOCK` 仅测试/演示 |
| `collect` | 汇总 | 步骤数/状态分布写入 `notes` |
| `verify` | 结构化验收 | `FAILED` / `NEEDS_INPUT` / `DISPATCH_REQUIRED` / `COMPLETE` |

验收口径：

- 任一步骤 `FAILED` → `FAILED`；
- 任一步骤缺字段（`missingFields`）→ `NEEDS_INPUT` 并回问；
- 全部 `DISPATCH_REQUIRED` → `DISPATCH_REQUIRED`（把计划交回 Java）；
- 全部 `SUCCEEDED` → `COMPLETE`。

### 4.1 明确不做

- Python 不直连子 Agent，不持有子 Agent 令牌；
- 不做 LLM 参数抽取（后续把现有 `/route`、`/repair` 的结果作为 `plan` 输入）；
- 不做自动重试循环，避免骨架阶段产生不可控多轮调用；
- 不引入 Kafka/Neo4j/向量库；事件仍走进程内总线。

---

## 5. 测试与验收（2026-11-26）

- 新增 Java 单测/集成测试：
  - `InMemoryEventBusTest`：有界缓冲、消费者故障隔离、关闭开关（3 项）；
  - `FlywheelSampleServiceTest`：终态消费、幂等去重、JSONL 落盘（1 项）；
  - `TaskEventFlowTest`：requested → confirmation.requested → completed/failed，
    载荷不含确认令牌，飞轮样本可查询（2 项）；
  - `PrototypeSmokeTest`：ADMIN 事件/飞轮查询与普通用户 403（1 项）。
- 新增 Python 测试 `test_orchestrator.py`：
  - 合同路由、Mock 成功/失败、缺字段回问、跨域歧义、上层计划优先、未知能力失败、HTTP 端点/非法模式（8 项）。
- 全量：Java **95/95**、Python **23/23**（2026-11-26）。
- 未覆盖：Kafka/Redis Stream 适配、多实例有序、LLM 参数抽取、自动重试、前端展示。

---

## 6. 下一步

1. **LLM 参数抽取接入编排**：复用 `/internal/v1/ai/route` 生成 `plan`，`/repair` 处理缺字段；
2. **Java 消费 DISPATCH_REQUIRED**：把编排结果接回 `TaskService`，形成「LLM 编排 + Java 执行」闭环；
3. **Outbox + Kafka/Redis Stream 适配**：先登记资源，再替换 `InMemoryEventBus`，消费端幂等；
4. **飞轮样本入库/查询**：从 JSONL 迁到 `wa_flywheel_sample`，接评测与偏好数据；
5. **前端可观测**：管理端展示最近事件、飞轮样本、编排计划与验收结果。
