# wuye-agent

物业主 Agent（单对话框编排 Agent）。用一个对话框统一接入物业运维、合同审查等子 Agent，并提供可扩展的 Agent 接入层。

## 文档索引

| 文档 | 内容 |
|---|---|
| [`docs/01-需求与功能模块清单.md`](docs/01-需求与功能模块清单.md) | 定位、角色、用户故事、功能模块、MVP 验收 |
| [`docs/02-技术选型与架构设计.md`](docs/02-技术选型与架构设计.md) | 技术栈、总体架构、Agent 分层、路由、安全、演进 |
| [`docs/03-数据库设计文档.md`](docs/03-数据库设计文档.md) | 16 张表、索引、DDL、Flyway、数据生命周期 |
| [`docs/04-API接口设计清单.md`](docs/04-API接口设计清单.md) | 用户端/内部 API、SSE 事件、子 Agent 映射、错误码 |
| [`docs/05-最终设计方案与开发规范.md`](docs/05-最终设计方案与开发规范.md) | 代码结构、编码/测试/Git 规范、启动、阶段计划、ADR |
| [`docs/06-Agent接入与扩展规范.md`](docs/06-Agent接入与扩展规范.md) | Canonical v1、Adapter SPI、新 Agent 接入 SOP、自定义 Agent 边界 |
| [`docs/07-底座通用加固.md`](docs/07-底座通用加固.md) | H1 注册持久化 / H2 表单聚合 / H3 权限声明的设计与状态 |
| [`docs/memory/00_project_brief.md`](docs/memory/00_project_brief.md) | 项目 brief 与当前状态 |
| [`docs/demo/README.md`](docs/demo/README.md) | 面试材料索引：必读 4 份、附录、不建议展示的材料 |
| [`docs/demo/面试演示脚本.md`](docs/demo/面试演示脚本.md) | 8 分钟演示流程、苍穹数码场景映射、高频问答、风险兜底 |
| [`docs/demo/业务发现与AI全栈叙事.md`](docs/demo/业务发现与AI全栈叙事.md) | 以“业务发现 × AI 全栈交付”为主线的 10 分钟叙事、场景机会地图、30/60/90 天计划 |
| [`docs/demo/平台能力评估.md`](docs/demo/平台能力评估.md) | A2A / 前端 / 后端能力评估、设计 vs 实现差距、面试话术、提分动作 |
| [`docs/demo/A2A自修复与记忆.md`](docs/demo/A2A自修复与记忆.md) | A2A 失败反馈、自修复决策矩阵、Schema 校验、Actor/Trace、记忆系统边界 |
| [`docs/demo/A2A渐进架构.md`](docs/demo/A2A渐进架构.md) | 核心稳定 + 协议 facade、Agent Card、A2A 标准映射、分阶段演进路线 |
| [`docs/demo/可扩展多Agent演示.md`](docs/demo/可扩展多Agent演示.md) | 第三个 Agent 接入三步法、自动复用清单、完整 GIS 演示链路 |
| [`docs/demo/动态安装Agent.md`](docs/demo/动态安装Agent.md) | 运行时安装 Agent、完整巡检 Agent 能力、受控安装边界 |
| [`docs/demo/演示预置问题.md`](docs/demo/演示预置问题.md) | 演示输入清单、面试官追问的自省问题、企业 Agent URL、记忆演示 |
| [`docs/demo/面试问答与技术选型.md`](docs/demo/面试问答与技术选型.md) | 技术栈全景、选型取舍、踩坑与解决、40+ 面试问题、GIS/苍穹映射 |
| [`docs/demo/真实Agent联调方案.md`](docs/demo/真实Agent联调方案.md) | 物业 8402 / 合同 8400 的真实 A2A 联调步骤、令牌/端口/风险 |

## 输入依据

- [`PropertyMaintenanceAgent-主Agent接入使用说明.md`](PropertyMaintenanceAgent-主Agent接入使用说明.md)
- [`使用手册.md`](使用手册.md)

## 原型（已可运行）

位置：`apps/java-bff/`。纵向切片：Java BFF + MySQL 持久化 + Mock/真实 HTTP Adapter + 原生单对话框 UI。

```bash
cp .env.example .env && chmod 600 .env

# 测试（内存 profile，随机端口，不占业务端口）
mvn -f apps/java-bff/pom.xml test
# 当前：Java 66/66 passed；Python AI 15/15；端到端 smoke 26 项

# 一键演示（强制 mock 子 Agent，自动重打包；默认开启 LLM）
bash scripts/start-demo.sh
# 演示链路冒烟：26 项检查
bash scripts/smoke-demo.sh
# 全功能 E2E：101 项真实 HTTP/SSE/异步/动态安装/记忆/RBAC 检查
python3 -I scripts/e2e_full.py
# 离线兜底：纯规则路由
DEMO_AI=0 bash scripts/start-demo.sh

# 手动启动（8600 需先登记 RESOURCES.md；脚本会 source .env）
bash scripts/start-prototype.sh
```

已实现：登录 + JWT + 三级 RBAC（用户/工人/管理员，权限表驱动）、个人中心动态表单（表单由子 Agent Manifest 提供）、按权限过滤 Agent/能力卡片；单对话框、SSE、LLM 主导路由（DeepSeek/OpenAI 兼容）、计划拆解（只读前置步骤 + 最终写操作）、**多轮补参状态**、**主数据枚举映射（ticketTypes/priorities 等）**、**A2A 失败自修复（schema 前置校验 + 追问/修正/只读重试/换能力/无能力兜底）**、**Actor/Trace 透传到子 Agent**、**Agent Card 发现层（`/.well-known/agent-card.json` + 管理端 A2A 总览）**、**第三个演示 Agent（时空资产：地块/遥感变化图斑/巡查任务，验证“新增 Agent 不改主流程”）**、**运行时安装/卸载 Agent（侧边栏「Agent 状态」＋：安装完整巡检报告 Agent / 从 Agent Card URL 安装 / 内置政策问答与客服工单企业 Agent；动态 Agent 右侧 × 可卸载）**、**主 Agent 自省工具（`platform.catalog` / `platform.a2a` / `platform.memory`，返回真实注册表与记忆数据）**、**动态 Agent 记忆注入（主 Agent 注入 `_memory`，子 Agent 不各自维护记忆）**、**自然语言语义解析（名称/描述 → ticketNo/contractId/parcelNo，唯一命中继续、多候选追问）**、**批量任务执行（一次请求多个独立操作，逐项解析 ID、生成多张确认卡）**、子 Agent 校验失败回传 LLM 自动修正参数、规则仅作 LLM 不可用时兜底；**子 Agent 原始 output 保留在任务 API/事件中，聊天区按中文字段名与中文枚举展示，并附 LLM 自然语言解释（不改写数据）**；**子 Agent 能力以工具形式暴露**（`GET /api/v1/tools`、`POST /api/v1/tools/{name}/execute`）；**ADMIN 数据看板**（任务总数/成功率/待确认/平均耗时/人工介入率/工单漏斗/能力分布/LLM token 与成本）；**操作审计**（登录/任务提交/确认/取消/会话删除/工具调用，写入 `wa_audit_log`，ADMIN 可查）；**进程内限流**（每用户/IP 每分钟 300 次，可配置）；**本地 CI 门禁与备份脚本**；**会话级摘要记忆**（落库、仅当前会话使用，不跨会话检索）；**全局用户画像**（稳定偏好，独立于子 Agent 个人资料，可在个人中心查看/清除）；**人工介入队列**（待确认/失败/拒绝任务自动入队，工人/管理员可认领、处理、关闭）；**站内通知中心**（`wa_notification`、未读角标、全部已读、SSE 实时推送；外部 Agent 用内部令牌写入）；写操作确认/拒绝、异步合同审查、取消、任务事件时间线、确认令牌不出服务端；会话删除（级联删除消息/任务/事件）；会话/消息/任务/事件落 MySQL 并跨重启保留；`WUYE_AGENT_MODE=http` 接真实物业 8402 / 合同 8400。

演示账号：`admin/admin123`、`worker/worker123`、`worker2/worker2123`、`senior/senior123`、`contractor/contractor123`、`supervisor/supervisor123`、`user/user123`、`user2/user2123`。
合同 Agent 仅管理员可见；普通用户只能建单/查自己的工单；工人/施工方/监理可接单/处理/解决，不能建单；管理员全部。
业务角色是层级而不是具体人：OA 等外部 Agent 用 `actorId` 做本地绑定，底座只透传身份，不替它们定义业务角色。

```bash
# 接入真实大模型
# 1) 在 .env 填 DEEPSEEK_API_KEY
# 2) 启动 Python AI（默认 8700，需登记 RESOURCES.md）
bash scripts/start-python-ai.sh
# 3) 打开 LLM 路由并重启 Java
WUYE_AI_ENABLED=true bash scripts/start-prototype.sh
```

原型边界：演示默认使用 Mock 子 Agent（真实 8402/8400 启动后可用 `WUYE_AGENT_MODE=http` 联调）、Manifest 静态、无 Redis/MinIO、任务事件轮询；LLM 需在 `.env` 配 `DEEPSEEK_API_KEY`，未配置时自动回退规则路由。

站内通知中心：

- 表 `wa_notification`（Flyway V13）；用户接口 `/api/v1/notifications`、SSE `/api/v1/notifications/stream`；
- 外部 Agent 通过 `POST /api/v1/internal/notifications` + `X-Internal-Token` 写入；
- 底座 `.env` 配 `NOTIFY_INTERNAL_TOKEN`，OA 的 `BASE_NOTIFY_TOKEN` 与之一致；
- 顶栏铃铛显示未读，支持全部已读与点击跳转；SSE 断线重连 + 30 秒轮询兜底。

签名身份断言：

- 配置 `IDENTITY_ASSERTION_SECRET` 后，底座对动态 Agent 调用附带 `X-Identity-Assertion`；
- 内容为 `actorId + roles + tenantId + aud + exp` 的 HMAC-SHA256 签名，120 秒过期；
- 子 Agent 可自行校验；OA 已接入（`BASE_IDENTITY_SECRET` 与底座一致）。
- 该断言只证明身份，不替代各 Agent 的本地业务授权。

聊天附件：

- 对话框支持上传 PDF/TXT/MD/CSV/PNG/JPG（10MB 上限）；
- 主 Agent 用 PDFBox 抽文本层，判定 `SIMPLE_TEXT` / `COMPLEX_MULTIMODAL`；
- 按用户描述 + 附件内容判定 OA / 合同领域，把 `attachmentId + documentText + userIntent` 交给子 Agent；
- 主 Agent 只做结构化验收：缺信息回问，不替子 Agent 编业务结论；
- 扫描件/图片需要 OCR/视觉适配器，当前未接；
- 合同附件需合同 Agent 8400 启动，或 `WUYE_AGENT_MODE=mock` 演示。

事件出口与编排骨架（第一阶段，2026-11-26）：

- 事件契约 v1：`agent.task.requested / confirmation.requested / completed / failed / cancelled`；
  载荷不含确认令牌，信封含 `traceId/actorId/tenantId/subject/idempotencyKey`；
- Java `EventPublisher` / `EventConsumer` + 进程内 `InMemoryEventBus`：有界缓冲、消费者故障隔离、
  事件故障不影响任务主流程；ADMIN 可查 `GET /api/v1/admin/events/recent`；
- **Outbox**：任务事件先写 `wa_event_outbox`（V15）再投递，5 秒 relay 补发 PENDING；
  MySQL profile 下跨重启不丢，单事件最多重试 5 次；
- **Redis Stream**：`EVENT_BACKEND=redis` 时 Outbox relay 写入 `wuye_agent:events`，
  订阅者 `XREADGROUP` 消费后投递本地总线；前缀/Stream 已登记 `RESOURCES.md`；
  默认 `memory`，不启用 Redis；
- **Stream 健康与死信**：`GET /api/v1/admin/events/stream` 返回长度/pending/DLQ/消费计数；
  坏消息写 `wuye_agent:events:dlq` 后 ACK，DLQ 写失败则不 ACK；运行观测摘要显示 Stream 状态；
- 飞轮消费者把任务终态样本落 `data/flywheel/samples.jsonl`（默认/测试）或
  `wa_flywheel_sample`（mysql profile，V16），ADMIN 可查
  `GET /api/v1/admin/flywheel/samples`；只做数据出口，不接训练；
- Python AI 新增 LangGraph 编排骨架 `POST /internal/v1/ai/orchestrate`：
  `route → plan → dispatch → collect → verify`；`PLAN_ONLY` 只产出计划、由 Java 执行，
  `MOCK` 仅用于测试/演示；Python 仍不直连子 Agent；
- **编排闭环**：Java 在现有 LLM 路由后调用 `/orchestrate` 校验计划，消费 `DISPATCH_REQUIRED`
  执行，再把终态结果回传 `/orchestrate/verify`；SSE 输出 `orchestration` 事件（PLAN/VERIFY）。
  编排失败/超时自动回退现有流程（`wuye.ai.orchestration-enabled` 可关闭）；
- **可观测**：聊天区展示编排/验收徽标；ADMIN 用户菜单「运行观测」查看最近编排
  （mysql profile 落 `wa_orchestration_trace`，重启保留）、最近事件（总线缓冲或 Outbox 历史）、
  Outbox 状态与飞轮样本；打开时 5 秒自动刷新；
- **评测流水线**：`scripts/eval_flywheel.py` 从 JSONL 或 ADMIN 导出接口读取样本，
  生成成功率/耗时/错误/按 Agent 与能力的离线报告，并导出 `label=null` 的候选数据集；
  基线见 `docs/eval/2026-11-26-flywheel-baseline.md`；
- 本阶段未接 Kafka / Neo4j / 向量库 / 多实例分布式消费。详见 `docs/08-主Agent演进-事件契约与编排骨架.md`。

详见 [`apps/java-bff/README.md`](apps/java-bff/README.md)。

## 当前状态

- 设计文档已同步到子 Agent 最新契约：物业 `0.11.0`（任务级 Skills）、合同 `0.7.0`。
- 子 Agent P0 整改已落地：委托令牌签发、Manifest 元数据、健康/就绪、`clientTaskId` 回查、取消、事件 `seq/afterSeq`。
- 物业 0.11.0 适配：Manifest 版本化缓存/强制刷新、Skill 优先选择与 `skillId` 全链路透传、
  Agent Card 真 Skill 列表、`requiresConfirmation` 确认语义、提交期 400/60001 映射。
- 两个真实子 Agent（物业 8402 / 合同 8400）当前均未启动；演示默认使用 Mock，并新增第三个 Mock `geo-asset-agent` 验证扩展性。
- 主 Agent 候选端口：`8600`（Java BFF）、`8700`（Python AI）、`5182`（Web dev）。
- 物业子 Agent 已改为 `AGENT_SERVICE_PORT` 必填，登记 `8402`；需手动补 `RESOURCES.md`。
- 合同 `contract.resolve` 已上线；`contract.import` 仍为 `enabled=false`。
- 原型已完成：后端 + UI + 9 项 smoke 测试通过；下一步接真实 HTTP Adapter 与 MySQL/Flyway。

## 资源与边界

- 独立 MySQL 库 `wuye_agent_db`、Redis 前缀 `wuye_agent:`、MinIO bucket `wuye-agent`。
- 不直连任何子 Agent 的 MySQL/Redis/MinIO/Milvus。
- 子 Agent 内部令牌只在 Java 侧；Python 不持有、不调用子 Agent。
- 共享中间件（MySQL/Redis/MinIO）只连接使用，不重启、不清理。

## 下一步

1. 登记主 Agent 端口与 `wuye_agent_db` 到 `RESOURCES.md`；补 `8402` 登记。
2. 启动物业 8402 / 合同 8400，切换 `WUYE_AGENT_MODE=http` 做活体联调。
3. 接 Manifest 自动同步/版本校验，替换静态能力描述。
4. 启用并验证 LLM 路由（`/internal/v1/ai/route`）；补 LLM 生成/usage 落库。
5. 增加任务 SSE、审计、Agent 管理后台、Redis 确认令牌。
