# 00 项目 Brief

## 项目名称与目标

- **项目**：`wuye-agent`（物业主 Agent / 单对话框编排 Agent）
- **目标**：用一个对话框统一接入物业运维与合同审查两个领域子 Agent，并提供可扩展的 Agent 接入层。
- **首期子 Agent**：
  - `property-maintenance`：物业报修/工单/派单/现场/回访/知识（协议 `agent-task-v1` 0.8.0）
  - `contract-review`：合同审查/规则/知识（协议 `agent-task-v1` 0.7.0）

## 核心技术栈

- Java 21 + Spring Boot 3.5（BFF、鉴权、会话、Agent 层、审计）
- Python 3.13 + FastAPI（路由、槽位、生成）
- 原生 HTML/CSS/JS（单对话框 Web，SSE）
- MySQL 8（`wuye_agent_db`）、Redis（前缀 `wuye_agent:`）、MinIO（bucket `wuye-agent`）
- LLM：DeepSeek（`deepseek-chat`）

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

## 当前状态（2026-11-20）

- 五份设计文档 + Agent 扩展规范已完成并同步到最新契约。
- 子 Agent P0 已落地：物业 `0.8.0`、合同 `0.7.0`。
  - 合同：委托令牌签发、Manifest 元数据、`clientTaskId` 回查、启用 `CANCELLED`、`contract.resolve`；`contract.import` 仍禁用。
  - 物业：`AGENT_SERVICE_PORT` 必填（8402）、Manifest 元数据、`decision` 确认、`REJECTED` 终态、事件 `seq/afterSeq`、附件列表；异步声明 `db-recovery`。
- 两个子 Agent 当前均未启动（`8400/8401/8500` 已释放；`8200/8300/8402` 无进程）。
- 主 Agent 候选端口：`8600`（Java）、`8700`（Python）、`5182`（Web dev），均待登记。
- `RESOURCES.md` 的 `8402` 登记需用户手动补；主 Agent 不越界修改。
- 主 Agent 原型已完成：`apps/java-bff`，Java BFF + MySQL 持久化 + Mock/HTTP Adapter + 原生单对话框 UI。
  - `mvn -f apps/java-bff/pom.xml test`：20/20 passed（10 项 BFF smoke + 5 项 HTTP Adapter + 5 项 LLM 路由）。
  - 已实现：登录 + JWT + 三级 RBAC（用户/工人/管理员，权限表驱动）、个人中心动态表单（Manifest forms.personalInfo）、按权限过滤 Agent/能力卡片；LLM 主导路由、计划拆解、多轮补参、参数自动修正；子 Agent 原始 output 保留在 SSE `result`/任务 API，聊天区按中文字段名与中文枚举展示，并由 LLM `explain` 生成简短中文解释；工具 API `/api/v1/tools`；ADMIN 数据看板（成功率/待确认/平均耗时/人工介入率/工单漏斗/能力分布/LLM token 与成本，成本按可配置单价估算）；操作审计 `wa_audit_log`（登录/任务提交/确认/取消/会话删除/工具调用，ADMIN 查询）；进程内限流、`scripts/ci.sh`、`scripts/backup.sh`；会话级摘要记忆（`wa_chat_session.summary`，仅当前会话使用，不跨会话检索）；全局用户画像（`wa_user_memory_profile`，稳定偏好，`memory.profile.update/get` 主 Agent 工具，个人中心可查看/清除）；人工介入队列（`wa_human_intervention`，待确认/失败/拒绝自动入队，WORKER/ADMIN 认领/处理）；SSE、确认/拒绝、异步合同审查、取消、任务时间线、会话删除；确认令牌不出服务端。
  - 演示账号：admin/admin123、worker/worker123、user/user123；合同 Agent 仅管理员可见。
  - 权限：普通用户建单/查自己的工单；工人接单/处理/解决，不能建单；管理员全部。权限来自 `wa_role/wa_permission/wa_role_permission`，不是前端硬编码。
  - 持久化：`wuye_agent_db` + Flyway；会话/消息/任务/事件跨重启验证通过；令牌 AES-GCM 加密落库。
  - 真实 HTTP：`WUYE_AGENT_MODE=http` 接 8402/8400，支持方言、200/202、委托令牌、错误映射；子 Agent 停机时返回 `FAILED(DEPENDENCY)` 不崩溃。
  - LLM 路由：`apps/python-ai` FastAPI + DeepSeek/OpenAI 兼容；Java **LLM 主导、规则兜底**，支持多步计划（只读前置 + 最终写）、多轮 PendingIntent 补参、`masterdata.options` 枚举映射（ticketTypes/priorities 等）、子 Agent 校验失败回传 LLM 修正参数；LLM 失败 30s 熔断回退规则。Java 35/35、Python 11/11 测试通过。
  - DeepSeek 实网验证通过：`1栋大堂的灯不亮了` → `property.ticket.create`（LLM，确认后 SUCCEEDED）；`付款节点一般怎么审` → LLM 追问消歧。
  - 临时端口 smoke：首页/JS/CSS 200、派单确认链路成功、SSE 无 `confirmationToken`；MySQL 重启后数据保留。
  - 原型边界：无真实登录、Manifest 静态、无 Redis/MinIO、任务事件轮询；子 Agent 未启动，尚未活体联调；LLM 需用户填 `DEEPSEEK_API_KEY`。

## 关键决策

- Java BFF + Python AI；Python 只做推理，不调子 Agent。
- 独立 Agent 层：Registry + Adapter SPI + CapabilityCatalog + Runtime。
- `capabilityId` 命名空间化，解决 `knowledge.search` 冲突。
- 首期逐个接入 + 声明式注册；自定义 Agent 放到 P5，且必须是受控模板。
- SSE + 轮询降级；主 Agent 自己掌握 deadline/超时/重试。

## 2026-11-21 面试演示准备

- 目标：后天（2026-11-23）苍穹数码面试，8 分钟演示；定位为“可复用的智能体编排底座”，物业/合同只是演示子 Agent。
- 已恢复编译：`MetricsController` 补 `java.util.List` 导入；Java 测试 35/35、Python 11/11 通过。
- 新增 `scripts/start-demo.sh`：一键停止本项目旧进程、重打包、强制 mock 子 Agent、默认启动 Python LLM、健康等待；`DEMO_AI=0` 可离线回退规则路由。
- 新增 `scripts/smoke-demo.sh`：16 项链路冒烟（健康/登录/查询/建单确认/合同异步/看板/告警/人工队列/审计），已验证 `PASS=16 FAIL=0`。
- 新增 `scripts/reset-demo-data.sh`：只清 `wuye_agent_db` 演示数据，保留账号/角色/权限，需 `--yes` 确认。
- Mock 模式补齐主数据枚举（ticketTypes/priorities/locationTypes/indoorPublicAreas），自然语言“1栋大堂的灯不亮了”可直接映射 `LIGHTING` 并进入确认。
- Mock 物业补齐接单/解决/验证/关闭/取消/挂起/恢复/结束/评论/材料/回访/导出，避免演示中因边缘能力中断。
- 演示文档：`docs/demo/面试演示脚本.md`（流程、苍穹场景映射、Q&A、风险兜底、两天计划）。
- 新增叙事文档 `docs/demo/业务发现与AI全栈叙事.md`：以“业务发现 × AI 全栈交付”为主线，含 10 分钟结构、四个苍穹场景机会地图、30/60/90 天计划、AI 协作话术。
- 数据看板新增“业务价值”区：自动化完成率、预计节省工时（8 分钟/单假设）、单任务 LLM 成本。
- 新增 `docs/demo/平台能力评估.md`：A2A 编排 4/5、标准互操作 1.5/5、前端 Demo 4/5、后端原型 3.5/5；明确“设计有、代码未实现”的差距（动态注册、Manifest 同步、Agent Card、streamEvents、附件）。
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

## 下一步

1. 登记主 Agent 端口与 `wuye_agent_db` 到 `RESOURCES.md`；补 `8402` 登记。
2. 启动物业 `8402` / 合同 `8400`，`WUYE_AGENT_MODE=http` 做活体联调。
3. 接 Manifest 自动同步/版本校验，替换静态能力描述。
4. 接 Python AI 路由（`/internal/v1/ai/route`），规则保留为第一层。
5. 补任务 SSE、审计、Agent 管理后台；确认令牌迁 Redis。
6. 并行推动子 Agent 后续项：异步持久化、usage 计量、tenantId、指标标准化。
