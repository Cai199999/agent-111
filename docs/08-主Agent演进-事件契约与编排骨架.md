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

## 6. 第二阶段：LLM 计划接入与执行闭环（2026-11-26）

### 6.1 链路

```text
LLM /route（现有）
  → RouteDecision.plan（最多 3 步，最后一步为写/查询）
  → Python /orchestrate（PLAN_ONLY）
  → DISPATCH_REQUIRED
  → Java executePlan（只读前置 + 最终能力，现有 TaskService）
  → Python /orchestrate/verify（回传步骤结果）
  → SSE orchestration(PLAN/VERIFY)
```

### 6.2 Java 接入

```text
orchestration/
  OrchestrationResult.java       # Python 返回的计划/步骤/验收
  OrchestrationOutcome.java      # Java 消费视图
  OrchestrationGateway.java      # 编排网关接口（便于单测注入）
  HttpOrchestrationGateway.java  # HTTP 实现，3s 超时 + 30s 熔断
  OrchestrationService.java      # 权限裁剪、计划回退、执行后验收
```

- 开关：`wuye.ai.enabled` + `wuye.ai.orchestration-enabled`（默认 true，仅 AI 开启时生效）；
- **失败开放**：接口 404/超时/非 2xx 一律跳过编排，回退现有同步流程；
- **权限裁剪**：只把当前用户可见能力发给 Python；未授权能力仍走原鉴权；
- **不适用范围**：主 Agent 自省工具（`platform.*`）、批量工具、附件链路（已有专用验收）；
- **执行前**：`NEEDS_INPUT/FAILED` 直接回问，不执行；`DISPATCH_REQUIRED` 使用 Python 校验后的计划；
- **执行后**：只对终态任务验收；`NEEDS_INPUT` 以 `orchestration` + `feedback` 事件暴露，不覆盖原任务结果。

### 6.3 Python 验收接口

```http
POST /internal/v1/ai/orchestrate/verify
```

```json
{
  "traceId": "trc-...",
  "plan": [{"capabilityId": "property.ticket.query", "args": {"status": "PENDING"}}],
  "steps": [
    {"capabilityId": "property.ticket.query", "status": "SUCCEEDED",
     "output": {"items": []}, "error": null, "missingFields": []}
  ]
}
```

返回 `{verdict, clarification, notes}`，只做结构化验收，不重新调用子 Agent。

### 6.4 真实验证（2026-11-26）

- 启动 Python AI 8700 + Java 8600（`WUYE_AI_ENABLED=true`，mysql profile）；
- `查询我的待处理工单`：
  - SSE `orchestration PLAN`：`DISPATCH_REQUIRED`，计划 `property.ticket.query`；
  - Java 执行时 8402 未启动 → 任务失败；
  - SSE `orchestration VERIFY`：`FAILED`，澄清为 `ConnectException`；
- 结论：计划、执行、验收闭环真实跑通；子 Agent 不可用时流程不被编排拖垮。

### 6.5 测试

- 新增 Java：`OrchestrationServiceTest` 5 项、`HttpOrchestrationGatewayTest` 3 项（请求体/令牌/计划解析/验收解析/失败回退）；
- 新增 Python：`/orchestrate/verify` 4 项（COMPLETE/NEEDS_INPUT/FAILED/HTTP）；
- 全量：Java **107/107**（含 Outbox 4 项）、Python **27/27**（2026-11-26）。

### 6.6 边界

- 验收目前只覆盖最终任务，只读前置步骤未逐个回传；
- `NEEDS_INPUT` 只发事件/提示，不自动重新规划或重试；
- 前端尚未渲染 `orchestration` 事件；管理端展示留到下一阶段；
- 未接 Outbox/Kafka，事件与编排仍是单进程。

## 7. Outbox 持久化（2026-11-26）

### 7.1 目标

先解决“进程崩溃/重启导致任务事件丢失”，不引入 Kafka：

```text
TaskService → OutboxEventPublisher
                ├─ append → wa_event_outbox (PENDING)
                ├─ dispatch → InMemoryEventBus（消费端：飞轮等）
                └─ markDispatched
                定时 relay：扫描 PENDING → 重新 dispatch
```

### 7.2 实现

| 组件 | 说明 |
|---|---|
| `V15__event_outbox.sql` | `wa_event_outbox`：事件信封全字段 + `payload_json` + 状态/尝试次数/错误 |
| `EventOutboxStore` | append / pending / markDispatched / markFailed / stats |
| `InMemoryEventOutboxStore` | 默认/测试 profile；仅进程内“先记录后投递” |
| `MySqlEventOutboxStore` | mysql profile；跨重启恢复，单事件最多重试 5 次 |
| `OutboxEventPublisher` | `@Primary` EventPublisher；同步投递 + 每 5 秒 relay |
| `EventSchedulingConfig` | 只为本项目 relay 开启调度 |

- 投递语义：至少一次；消费端按 `eventId` 幂等；
- 写 Outbox 失败不阻断任务，仍尝试本次投递；
- `attempts` 超上限后标记 `FAILED`，不无限重试；
- 管理端 `/api/v1/admin/events/recent` 新增 `outbox` 统计。

### 7.3 配置

```dotenv
EVENT_OUTBOX_ENABLED=true
EVENT_OUTBOX_RELAY_MS=5000
EVENT_OUTBOX_MAX_ATTEMPTS=5
```

### 7.4 测试与边界

- `OutboxEventPublisherTest` 4 项：同步投递、失败重试至 FAILED、重启补发、关闭直投；
- MySQL 表在 `mysql` profile 启动时由 Flyway 应用；
- 真实验证（2026-11-26）：Flyway v14→v15 成功；一次聊天产生 `agent.task.requested/failed`，
  outbox 均为 `DISPATCHED/attempts=1`；手工插入一条 PENDING 事件后重启应用，
  relay 在约 7 秒内将其置为 `DISPATCHED`；
- 边界：relay 为单实例（多实例需分布式锁或改由 Kafka 消费位点接管）；
  consumer 内部失败只告警，不自动重投；事件顺序按单 relay + `created_at` 近似保证。

## 8. 飞轮样本入库（2026-11-26）

### 8.1 实现

| 组件 | 说明 |
|---|---|
| `V16__flywheel_sample.sql` | `wa_flywheel_sample`：任务/输入/输出/错误/耗时/来源事件 |
| `JsonlFlywheelSampleStore` | 默认/测试 profile；JSONL 追加写 |
| `MySqlFlywheelSampleStore` | mysql profile；按 `event_id` 去重，可按任务/Agent 查询 |

- 样本消费者不变：订阅 `completed/failed/cancelled` 终态事件；
- 管理端 `GET /api/v1/admin/flywheel/samples` 同时返回 `samples` 与 `stats`；
- JSONL 仍是本地默认，MySQL 用于演示环境与后续评测流水线。

### 8.2 真实验证（2026-11-26）

- Flyway v15→v16 成功；
- 一次 `查询我的待处理工单` 生成 3 条样本（初始 + 自修复重试），均为 `FAILED`；
- 样本包含 `input`、`error{code,category,retryable,message}`、`durationMs`；
- ADMIN API 能查到同样数据。

### 8.3 边界

- 尚未接评测流水线、样本标注、训练/偏好数据导出；
- 样本含业务明文，只允许内网存储；保留期/脱敏策略未实现。

## 9. 前端可观测（2026-11-26）

### 9.1 聊天区

- 助手气泡新增 `orchestration-badge`；
- 收到 SSE `orchestration` 事件时展示：阶段（编排/验收）、`verdict` 中文标签、
  计划能力链或步骤状态、`clarification`；
- 同一气泡只保留最新状态：PLAN 短暂显示，VERIFY 覆盖为最终验收；
- 失败用红色样式，验收用蓝色、计划用紫色，不新增弹窗打断对话。

### 9.2 管理端「运行观测」

用户菜单新增 ADMIN 可见的「运行观测」：

- **最近事件**：优先取进程内总线；重启后缓冲为空时回退 Outbox 历史；
- **Outbox**：backend、已投递数、PENDING 数；
- **飞轮样本**：能力、任务号、状态、耗时、发生时间；
- 数据全部来自既有 ADMIN 接口，只读，不提供重放/删除按钮。

### 9.3 验证（2026-11-26）

- 无头 Chromium + CDP 实测：
  - 重启后「运行观测」显示 Outbox `已投递 13 · PENDING 0`、3 条飞轮样本，
    最近事件来自 Outbox 历史；
  - 聊天发送 `查询我的待处理工单` 后出现 `验收 FAILED（失败）` 徽标，
    内容为 `property.ticket.query=FAILED · ConnectException`；
- JS/HTML 语法检查通过；全量 Java **107/107**。

### 9.4 边界

- `orchestration` 结果目前只走 SSE，不落库，历史会话不重放编排状态；
- 管理端为打开时拉取，无自动刷新/分页；
- 未做多用户实时观测与告警。

## 10. 评测流水线（2026-11-26）

### 10.1 入口

```bash
# 默认 JSONL（默认/测试 profile）
python3 -I scripts/eval_flywheel.py --input data/flywheel/samples.jsonl

# MySQL profile：走 ADMIN 导出接口（最多 2000 条）
python3 -I scripts/eval_flywheel.py \
  --api http://127.0.0.1:8600 --token "$WUYE_TOKEN" --limit 1000 \
  --since 2026-11-26T01:43:09Z \
  --report docs/eval/2026-11-26-flywheel-baseline.md \
  --dataset-out data/flywheel/dataset-20261126.jsonl
```

- 数据源：JSONL 或 `GET /api/v1/admin/flywheel/export`；
- 时间过滤：`--since` / `--until`（ISO8601）；
- 去重：按 `eventId`；
- 输出：终端摘要、Markdown 报告、候选数据集 JSONL、原始指标 JSON。

### 10.2 指标

- 样本数、去重任务数、会话数、时间范围；
- 状态分布、成功率、失败/拒绝率；
- 耗时 avg / P50 / P95 / max；
- 错误码/分类分布（用户拒绝归一为 `USER_REJECTED/CONFIRM`）；
- 按 Agent、按能力的调用/成功/失败/平均耗时。

### 10.3 数据集

- 候选数据集保留 `input/output/error/status/durationMs`；
- `label=null`、`reviewStatus=UNREVIEWED`；
- 明确“运行痕迹 ≠ 金标”，人工复核后才能进入评测/训练。

### 10.4 基线（2026-11-26，开发库 mock）

- 报告：`docs/eval/2026-11-26-flywheel-baseline.md`；
- 5 条样本：4 SUCCEEDED、1 REJECTED；成功率 80%；
- P50 60 ms / P95 212.2 ms / max 217 ms；
- 3 个 Agent：property/contract/geo；拒绝样本归一为 `USER_REJECTED`；
- 测试：`scripts/test_eval_flywheel.py` **6/6**。

### 10.5 边界

- 样本量小且来自自造数据，不代表生产分布；
- 无人工标注/金标，不能计算分诊准确率、答案正确率；
- 未接 CI 门禁、趋势对比、告警阈值；
- 候选数据集含业务明文，只允许内网使用。

## 11. 下一步

1. **Kafka/Redis Stream 适配**：先登记资源，再替换 relay 下游，消费端幂等表；
2. **标注与金标评测**：人工复核候选数据集，补分诊/答案正确率；
3. **编排增强**：回传只读前置步骤结果、`NEEDS_INPUT` 自动重新规划一次；
4. **观测增强**：编排结果落库/历史回放、管理端自动刷新与告警；
5. **多实例**：按 `subject` 分区、消费端幂等表、SSE 跨实例。
