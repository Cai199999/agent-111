# 00 项目 Brief

## 项目名称与目标

- **项目**：`wuye-agent`（主 Agent / 单对话框编排 Agent）
- **目标**：用一个对话框统一接入物业、合同、时空资产等子 Agent，并提供可扩展、可运行时安装/卸载的 Agent 接入层。
- **子 Agent**：
  - `property-maintenance`：物业报修/工单/派单/现场/回访/知识（协议 `agent-task-v1` 0.9.0）
  - `contract-review`：合同审查/规则/知识（协议 `agent-task-v1` 0.7.0）
  - `geo-asset-agent`：地块/遥感变化图斑/巡查任务（演示用 Mock，`gis-v01`）
  - 运行时安装：`inspection-agent`、`policy-qa-agent`、`customer-service-agent`（模板/Agent Card URL）

## 核心技术栈

- Java 21 + Spring Boot 3.5（BFF、鉴权、会话、Agent 层、审计）
- Python 3.13 + FastAPI（路由/解释/摘要/画像/自修复）
- 原生 HTML/CSS/JS（单对话框 Web，SSE）
- MySQL 8（`wuye_agent_db`）；Redis/MinIO 为规划项，当前未接入
- LLM：DeepSeek（`deepseek-chat`）
- 当前状态（2026-11-21）：Java 66/66、Python 15/15、smoke 26/26、E2E 101/101；A2A 为 Agent Card discovery-only；动态安装为内存态

## 核心功能模块

1. 统一身份与会话
2. 单对话框交互（SSE、任务卡片、确认卡片）
3. Agent 注册与 Manifest 发现
4. 能力目录与路由（LLM 主导、计划拆解、参数修正、规则兜底、消歧）
5. 任务执行与状态归一化
6. 人工确认（绝不自动确认）
7. 事件与任务时间线（SSE + 轮询降级）
8. 附件代理上传
9. 审计与可观测
10. Agent 管理后台
11. 扩展接入（Canonical v1 + Adapter SPI）
12. 受控自定义 Agent（远期）

## 关键约束

- 不修改、不直连任何子 Agent 的 MySQL/Redis/MinIO/Milvus。
- 子 Agent 内部令牌与委托密钥只在 Java 侧；Python 不持有、不调用子 Agent。
- 确认令牌只存服务端（Redis TTL），浏览器只见 `taskNo`。
- 写操作必须用户显式确认，主 Agent 绝不自动确认。
- 主 Agent 资源独立：库/前缀/bucket；端口需先登记 `RESOURCES.md`。
- 共享中间件只连接使用，不重启/不清理。

## 历史状态（2026-11-20，部分数字/边界已过时）

- 五份设计文档 + Agent 扩展规范已完成并同步到最新契约。
- 子 Agent P0 已落地：物业 `0.8.0` → `0.9.0`、合同 `0.7.0`。
  - 合同：委托令牌签发、Manifest 元数据、`clientTaskId` 回查、启用 `CANCELLED`、`contract.resolve`；`contract.import` 仍禁用。
  - 物业：`AGENT_SERVICE_PORT` 必填（8402）、Manifest 元数据、`decision` 确认、`REJECTED` 终态、事件 `seq/afterSeq`、附件列表；异步声明 `db-recovery`。
- 两个真实子 Agent 当前均未启动（`8400/8401/8500` 已释放；`8200/8300/8402` 无进程）；演示另有 Mock 时空资产/动态 Agent。
- 主 Agent 候选端口：`8600`（Java）、`8700`（Python）、`5182`（Web dev），均待登记。
- `RESOURCES.md` 的 `8402` 登记需用户手动补；主 Agent 不越界修改。
- 主 Agent 原型已完成：`apps/java-bff`，Java BFF + MySQL 持久化 + Mock/HTTP Adapter + 原生单对话框 UI。
  - `mvn -f apps/java-bff/pom.xml test`：20/20 passed（历史；当前 66/66）。
  - 已实现：登录 + JWT + 三级 RBAC（用户/工人/管理员，权限表驱动）、个人中心动态表单（Manifest forms.personalInfo）、按权限过滤 Agent/能力卡片；LLM 主导路由、计划拆解、多轮补参、参数自动修正；子 Agent 原始 output 保留在 SSE `result`/任务 API，聊天区按中文字段名与中文枚举展示，并由 LLM `explain` 生成简短中文解释；工具 API `/api/v1/tools`；ADMIN 数据看板（成功率/待确认/平均耗时/人工介入率/工单漏斗/能力分布/LLM token 与成本，成本按可配置单价估算）；操作审计 `wa_audit_log`（登录/任务提交/确认/取消/会话删除/工具调用，ADMIN 查询）；进程内限流、`scripts/ci.sh`、`scripts/backup.sh`；会话级摘要记忆（`wa_chat_session.summary`，仅当前会话使用，不跨会话检索）；全局用户画像（`wa_user_memory_profile`，稳定偏好，`memory.profile.update/get` 主 Agent 工具，个人中心可查看/清除）；人工介入队列（`wa_human_intervention`，待确认/失败/拒绝自动入队，WORKER/ADMIN 认领/处理）；SSE、确认/拒绝、异步合同审查、取消、任务时间线、会话删除；确认令牌不出服务端。
  - 演示账号：admin/admin123、worker/worker123、user/user123；合同 Agent 仅管理员可见。
  - 权限：普通用户建单/查自己的工单；工人接单/处理/解决，不能建单；管理员全部。权限来自 `wa_role/wa_permission/wa_role_permission`，不是前端硬编码。
  - 持久化：`wuye_agent_db` + Flyway；会话/消息/任务/事件跨重启验证通过；令牌 AES-GCM 加密落库。
  - 真实 HTTP：`WUYE_AGENT_MODE=http` 接 8402/8400，支持方言、200/202、委托令牌、错误映射；子 Agent 停机时返回 `FAILED(DEPENDENCY)` 不崩溃。
  - LLM 路由：`apps/python-ai` FastAPI + DeepSeek/OpenAI 兼容；Java **LLM 主导、规则兜底**，支持多步计划（只读前置 + 最终写）、多轮 PendingIntent 补参、`masterdata.options` 枚举映射（ticketTypes/priorities 等）、子 Agent 校验失败回传 LLM 修正参数；LLM 失败 30s 熔断回退规则。Java 35/35、Python 11/11（历史；当前 66/66、15/15）。
  - DeepSeek 实网验证通过：`1栋大堂的灯不亮了` → `property.ticket.create`（LLM，确认后 SUCCEEDED）；`付款节点一般怎么审` → LLM 追问消歧。
  - 临时端口 smoke：首页/JS/CSS 200、派单确认链路成功、SSE 无 `confirmationToken`；MySQL 重启后数据保留。
  - 当时边界：无真实登录（当前已实现 JWT/RBAC）、Manifest 静态、无 Redis/MinIO、任务事件轮询；子 Agent 未启动，尚未活体联调；LLM 需用户填 `DEEPSEEK_API_KEY`。

## 关键决策

- Java BFF + Python AI；Python 只做推理，不调子 Agent。
- 独立 Agent 层：Registry + Adapter SPI + CapabilityCatalog + Runtime。
- `capabilityId` 命名空间化，解决 `knowledge.search` 冲突。
- 首期逐个接入 + 声明式注册；自定义 Agent 放到 P5，且必须是受控模板。
- SSE + 轮询降级；主 Agent 自己掌握 deadline/超时/重试。

## 2026-11-21 面试演示准备

- 目标：后天（2026-11-23）苍穹数码面试，8 分钟演示；定位为“可复用的智能体编排底座”，物业/合同只是演示子 Agent。
- 已恢复编译：`MetricsController` 补 `java.util.List` 导入；Java 66/66、Python 15/15 通过。
- 新增 `scripts/start-demo.sh`：一键停止本项目旧进程、重打包、强制 mock 子 Agent、默认启动 Python LLM、健康等待；`DEMO_AI=0` 可离线回退规则路由。
- 新增 `scripts/smoke-demo.sh`：26 项链路冒烟（健康/登录/A2A/GIS/查询/建单确认/自修复/合同异步/看板/告警/人工队列/审计），已验证 `PASS=26 FAIL=0`。
- 新增 `scripts/reset-demo-data.sh`：只清 `wuye_agent_db` 演示数据，保留账号/角色/权限，需 `--yes` 确认。
- Mock 模式补齐主数据枚举（ticketTypes/priorities/locationTypes/indoorPublicAreas），自然语言“1栋大堂的灯不亮了”可直接映射 `LIGHTING` 并进入确认。
- Mock 物业补齐接单/解决/验证/关闭/取消/挂起/恢复/结束/评论/材料/回访/导出，避免演示中因边缘能力中断。
- 演示文档：`docs/demo/面试演示脚本.md`（流程、苍穹场景映射、Q&A、风险兜底、两天计划）。
- 新增叙事文档 `docs/demo/业务发现与AI全栈叙事.md`：以“业务发现 × AI 全栈交付”为主线，含 10 分钟结构、四个苍穹场景机会地图、30/60/90 天计划、AI 协作话术。
- 数据看板新增“业务价值”区：自动化完成率、预计节省工时（8 分钟/单假设）、单任务 LLM 成本。
- 新增 `docs/demo/平台能力评估.md`：A2A 编排 4/5、标准互操作 2/5、前端 Demo 4/5、后端原型 3.5/5；明确“设计有、代码未实现”的差距（动态注册、Manifest 同步、streamEvents、附件）。
- 2026-11-21 A2A 自修复落地：Python `/internal/v1/ai/recover`（CLARIFY/RETRY/ALTERNATIVE/NO_CAPABILITY）、Java `TaskRecoveryService`/`AgentErrorTranslator`/`RecoveryOutcome`、`InputSchemaValidator` 前置校验、`AgentAdapter` Actor/Trace 透传、人工队列中间失败自动关闭、前端 `feedback` 事件与错误脱敏。
- 2026-11-21 A2A 发现层：`GET /.well-known/agent-card.json`（公开脱敏）、`GET /api/v1/agents/{key}/card`（权限裁剪）、`GET /api/v1/admin/a2a/summary`（总览 + 健康）；前端头像菜单“A2A 能力”。只做 discovery-only，不实现 JSON-RPC；渐进架构见 `docs/demo/A2A渐进架构.md`。
- 2026-11-21 第三个演示 Agent：`geo-asset-agent`（Mock，GIS/遥感演示）。能力：`gis.parcel.query`、`gis.change.query`、`gis.patrol.create`（写操作需确认）。新增 `MockGisAdapter` + V10 权限迁移 + 规则关键词 + 前端字段/快捷入口；主流程未改，验证“新增 Agent 只做领域适配”。
- 2026-11-21 动态安装 Agent（演示版）：侧边栏「Agent 状态」＋ → 模板安装 `inspection-agent`（巡检报告：查询/计划/异步生成/异步导出）或从 Agent Card URL 安装 Canonical v1 HTTP Agent；`AdapterRegistry.register/unregister`、`AgentInstallService`、动态能力管理员授权；内存态，重启消失。
- 2026-11-21 平台自省与企业 Agent：新增主 Agent 工具 `platform.catalog`/`platform.a2a`/`platform.memory`（真实注册表/记忆）；内置 `/demo-agents/policy`、`/demo-agents/customer` 两个真实 HTTP 企业 Agent，可从 Agent Card URL 安装；动态 Agent 调用时主 Agent 注入 `_memory`，子 Agent 输出 `memoryUsed`。
- 2026-11-21 自然语言语义解析：用户不必输入内部编码；`ChatService.semanticResolve` 在缺 ticketNo/contractId/parcelNo 时先调只读查询，唯一命中自动合并、多个候选追问；`mergeOutput` 支持单候选列表；工单查询/派单/GIS 地块/图斑支持关键词过滤；选项目录补 workers（张师傅→10002）；LLM prompt 增加“不要索要内部编码，先语义检索”规则。
- 2026-11-21 批量任务：新增主 Agent `batch.execute`（items 每项一个操作）；`handleBatchTool` 逐项解析 ID、逐项建任务、生成多张确认卡；`HybridRouter` 对多个“派给”优先走确定性 batch，避免 LLM 压成单任务反复追问；前端按 `batchIndex` 渲染多张任务卡。
- 记忆系统现状：会话摘要 `wa_chat_session.summary`（仅当前会话）+ 全局画像 `wa_user_memory_profile`（稳定偏好，可查看/清除）；无向量长期记忆/跨会话 RAG。记忆由主 Agent 层统一注入动态 Agent，子 Agent 不各自维护记忆。
- 测试：Java 66/66、Python 15/15、端到端 smoke 26/26、全功能 E2E 101/101（`scripts/e2e_full.py`，覆盖健康/静态资源/登录/RBAC/A2A/平台自省/物业/合同/GIS/自修复/动态安装/企业 Agent/记忆/看板/审计/工具/事件）。
- 面试材料：`docs/demo/面试问答与技术选型.md`（技术栈全景、12 项选型取舍、12 个踩坑、40+ 问题、GIS/苍穹映射、边界话术）。
- 当前运行方式：`bash scripts/start-demo.sh`；已监听 8600/8700。演示前可选 `bash scripts/reset-demo-data.sh --yes` 清数据后重跑 smoke。

## 2026-11-24 底座通用加固（H1）

- 分支：`feat/base-hardening`；目标是在接入外部 Agent 前补齐三项通用能力，完成后打 `base-v1.1-2026-11-24` 重新冻结。
- H1 已实现：`wa_agent_registry` 表（V11）、`AgentRegistryStore` + 内存/MySQL 实现、`AgentRegistryBootstrap` 启动加载、安装写库/卸载标记/失败回滚、内部令牌 AES-GCM 加密落库。
- 启动加载不主动探活，避免子 Agent 不可达拖慢启动；健康由管理端按需查询。
- 测试：新增 `AgentRegistryBootstrapTest`，`AgentInstallServiceTest` 增加持久化断言；全量 Java 68/68 通过。
- MySQL 真实迁移与重启恢复尚未在共享 `wuye_agent_db` 执行，计划 H4 回归前验证。
- H2 已实现：`AgentAdapter.manifest()`（2 秒短超时）、`AgentManifestService`（物业 + 运行时 Agent，5 分钟缓存）、
  `/api/v1/profile/forms` 系列接口、个人中心 `#profileSections` 分段渲染；旧接口保留。
- H2 测试：`AgentManifestServiceTest` 3 项；`PrototypeSmokeTest` 27 项；全量 Java 72/72 通过。
- H3 已实现：V12 给 `wa_permission` 加 `source`；`DynamicPermissionService`（Noop/MySQL 两实现）；
  Card `permissions` 安装写权限、卸载只撤销 `AGENT:<key>` 来源；未知角色/内置冲突拒绝安装；安装/卸载写审计。
- H3 测试：`DynamicPermissionParseTest` 5 项；`AgentInstallServiceTest` 验证 grant/revoke；全量 Java 77/77 通过。
- H4 已完成（2026-11-25）：见下节。
- 设计文档：`docs/07-底座通用加固.md`；外部项目方案：`flow-agent/docs/08-底座通用加固方案.md`。

## 2026-11-25 底座 H4 验收与重新冻结

- 执行前备份 `wuye_agent_db` 到 `backups/wuye_agent_db_H4_20261125-091755.sql`（gitignore）。
- Flyway v10 → v12：V11 `wa_agent_registry`、V12 `wa_permission.source` 已在共享库应用。
- 测试：Java **77/77**、smoke **26/26**、E2E **101/101**（45.4s，LLM 开启、子 Agent mock）。
- H1 重启验证：安装 `inspection-agent` → 重启 `restored=1`；卸载 → `REMOVED` → 重启不恢复。
- H3 权限验证：临时 Card 安装写 3 条 `AGENT:<key>` 权限；BASE 只见只读能力，ADMIN 见读写；重启保留；卸载清零，内置 36 条权限不变。
- 全程 mock，未启动/写入真实物业 8402、合同 8400；契约与 Adapter 未改。
- 已打 annotated tag `base-v1.1-2026-11-24`，底座重新冻结；OA 只消费 v1.1。
- 未覆盖：G-02/G-03 角色透传、G-05 规则路由关键词、G-06 SSRF allowlist、G-07 安装审批。

## 2026-11-26 主 Agent 演进第一阶段（事件契约 + 编排骨架）

- 背景：主 Agent 下一步要补事件总线/编排/飞轮；先做可回滚的第一刀，不引入 Kafka/Neo4j/训练栈。
- 事件契约 v1 已冻结（`docs/08-主Agent演进-事件契约与编排骨架.md`）：
  - 信封：`eventId/eventType/eventVersion/occurredAt/source/traceId/actorId/tenantId/subject/idempotencyKey/payload`；
  - 类型：`agent.task.requested`、`confirmation.requested`、`completed`、`failed`、`cancelled`、`flywheel.sample.recorded`；
  - 至少一次、消费端幂等、单机有序、故障隔离；载荷禁止令牌/密钥/完整附件。
- Java 事件出口：`EventPublisher`/`EventConsumer`/`EventBus` + `InMemoryEventBus`（有界缓冲、同步扇出、
  单消费者故障隔离）；`TaskService` 只在状态迁移处发事件，轮询不重复发终态，确认令牌不进载荷。
- 飞轮：`FlywheelSampleService` 订阅终态事件，样本落 `data/flywheel/samples.jsonl`，
  有界幂等集合；ADMIN 查询 `/api/v1/admin/events/recent`、`/api/v1/admin/flywheel/samples`。
- Python 编排骨架：LangGraph `route → plan → dispatch → collect → verify`，
  端点 `/internal/v1/ai/orchestrate`；`PLAN_ONLY` 返回计划由 Java 执行，`MOCK` 仅测试/演示；
  Python 仍不直连子 Agent。新增 `langgraph` 依赖。
- 测试：Java 新增 7 项（事件总线 3、飞轮 1、任务事件 2、管理端回归 1），全量 95/95；
  Python 新增 8 项，`pytest` 23/23。
- 边界：未接 Kafka/Redis Stream、多实例有序、LLM 参数抽取、自动重试、前端事件展示。

## 2026-11-26 第二阶段：LLM 计划接入与执行闭环

- 链路：`LLM /route → RouteDecision.plan → Python /orchestrate(PLAN_ONLY) → DISPATCH_REQUIRED
  → Java executePlan → Python /orchestrate/verify → SSE orchestration(PLAN/VERIFY)`。
- Java `orchestration/`：`OrchestrationGateway` 接口 + `HttpOrchestrationGateway`（3s 超时、30s 熔断、失败开放）；
  `OrchestrationService` 做权限裁剪、计划回退、终态验收。
- 开关：`wuye.ai.orchestration-enabled`（默认 true，仅 `wuye.ai.enabled=true` 时生效）；
  主自省工具、批量工具、附件链路不进入编排；不可用时完全回退现有流程。
- 执行前 `NEEDS_INPUT/FAILED` 直接回问；`DISPATCH_REQUIRED` 使用 Python 校验后的计划；
  执行后仅终态任务验收，`NEEDS_INPUT` 以 `orchestration` + `feedback` 事件暴露，不覆盖原结果。
- Python 新增 `/internal/v1/ai/orchestrate/verify`，只做 collect+verify，不重新调用子 Agent。
- 真实验证：Python 8700 + Java 8600（AI 开启、mysql profile）；`查询我的待处理工单`
  → PLAN `DISPATCH_REQUIRED` → 8402 未启动任务失败 → VERIFY `FAILED(ConnectException)`，闭环跑通。
- 测试：Java 新增 8 项（OrchestrationService 5、HTTP 网关 3），全量 103/103；Python 新增 4 项，27/27。
- 边界：只验收最终任务；`NEEDS_INPUT` 不自动重规划；前端未渲染 `orchestration` 事件。

## 2026-11-26 事件 Outbox 持久化

- V15 `wa_event_outbox`：事件信封全字段 + payload_json + 状态/尝试次数/错误；
  `EventOutboxStore` 内存/MySQL 两实现；`OutboxEventPublisher` 为 `@Primary`，
  先 append 再同步投递，每 5 秒 relay 扫描 PENDING，最多重试 5 次后 FAILED。
- 语义至少一次，消费端按 eventId 幂等；写 Outbox 失败不阻断任务；
  管理端 `/api/v1/admin/events/recent` 增加 `outbox` 统计。
- 配置：`EVENT_OUTBOX_ENABLED/RELAY_MS/MAX_ATTEMPTS`。
- 测试：`OutboxEventPublisherTest` 4 项（同步投递、失败重试、重启补发、关闭直投）；
  Java 全量 107/107、Python 27/27。
- 真实验证：mysql profile 启动应用，Flyway v14→v15；一次聊天产生 requested/failed 事件，
  outbox 均 `DISPATCHED/attempts=1`；手工插入 PENDING 事件后重启应用，relay ~7 秒内补发成功。
- 边界：relay 单实例；consumer 内部失败只告警；MySQL 表需 mysql profile 启动时 Flyway 应用。

## 2026-11-26 飞轮样本入库

- V16 `wa_flywheel_sample`；`JsonlFlywheelSampleStore`（默认/测试）与
  `MySqlFlywheelSampleStore`（mysql profile，按 event_id 去重）两实现；
  消费者与 ADMIN 查询接口不变。
- 真实验证：Flyway v15→v16；一次工单查询生成 3 条样本（初始+自修复重试），
  含 input/error/durationMs；ADMIN API 可查。
- 边界：未接评测流水线/标注/导出；样本含业务明文，仅内网；无保留期/脱敏策略。

## 2026-11-26 前端可观测

- 聊天区新增 `orchestration` SSE 徽标：阶段/verdict/计划或步骤/澄清；同一气泡保留最新状态。
- 用户菜单新增 ADMIN「运行观测」：最近事件（总线为空时回退 Outbox 历史）、
  Outbox 已投递/PENDING、飞轮样本；全部只读。
- `EventOutboxStore.recent` 新增内存/MySQL 实现，管理端接口返回 `outboxEvents`。
- 无头 Chromium + CDP 实测：重启后观测页显示 Outbox 已投递、飞轮样本；
  聊天查询显示 `验收 FAILED（失败）` 徽标；Java 全量 107/107。
- 边界：编排结果只走 SSE 不落库；管理端打开时拉取，无自动刷新/分页/告警。

## 下一步

1. 事件通道升级：先登记资源，再做 Outbox + Kafka/Redis Stream，消费端幂等表。
2. 飞轮样本从 JSONL 迁 `wa_flywheel_sample`，接离线评测/偏好数据。
3. 管理端/前端展示 `orchestration`、事件与飞轮样本。
4. 编排增强：只读前置步骤结果回传、`NEEDS_INPUT` 自动重规划一次。
5. 原有：启动 8402/8400 活体联调、Manifest 自动同步、确认令牌迁 Redis。
