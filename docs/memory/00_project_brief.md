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
  - 已实现：登录 + JWT + 三级 RBAC（用户/工人/管理员，权限表驱动）、个人中心动态表单（Manifest forms.personalInfo）、按权限过滤 Agent/能力卡片；LLM 主导路由、计划拆解、多轮补参、参数自动修正；子 Agent 原始 output 保留在 SSE `result`/任务 API，聊天区按中文字段名与中文枚举展示，并由 LLM `explain` 生成简短中文解释；工具 API `/api/v1/tools`；ADMIN 数据看板（成功率/待确认/平均耗时/人工介入率/工单漏斗/能力分布/LLM token 与成本，成本按可配置单价估算）；操作审计 `wa_audit_log`（登录/任务提交/确认/取消/会话删除，ADMIN 查询）；SSE、确认/拒绝、异步合同审查、取消、任务时间线、会话删除；确认令牌不出服务端。
  - 演示账号：admin/admin123、worker/worker123、user/user123；合同 Agent 仅管理员可见。
  - 权限：普通用户建单/查自己的工单；工人接单/处理/解决，不能建单；管理员全部。权限来自 `wa_role/wa_permission/wa_role_permission`，不是前端硬编码。
  - 持久化：`wuye_agent_db` + Flyway；会话/消息/任务/事件跨重启验证通过；令牌 AES-GCM 加密落库。
  - 真实 HTTP：`WUYE_AGENT_MODE=http` 接 8402/8400，支持方言、200/202、委托令牌、错误映射；子 Agent 停机时返回 `FAILED(DEPENDENCY)` 不崩溃。
  - LLM 路由：`apps/python-ai` FastAPI + DeepSeek/OpenAI 兼容；Java **LLM 主导、规则兜底**，支持多步计划（只读前置 + 最终写）、多轮 PendingIntent 补参、`masterdata.options` 枚举映射（ticketTypes/priorities 等）、子 Agent 校验失败回传 LLM 修正参数；LLM 失败 30s 熔断回退规则。Java 32/32、Python 9/9 测试通过。
  - DeepSeek 实网验证通过：`1栋大堂的灯不亮了` → `property.ticket.create`（LLM，确认后 SUCCEEDED）；`付款节点一般怎么审` → LLM 追问消歧。
  - 临时端口 smoke：首页/JS/CSS 200、派单确认链路成功、SSE 无 `confirmationToken`；MySQL 重启后数据保留。
  - 原型边界：无真实登录、Manifest 静态、无 Redis/MinIO、任务事件轮询；子 Agent 未启动，尚未活体联调；LLM 需用户填 `DEEPSEEK_API_KEY`。

## 关键决策

- Java BFF + Python AI；Python 只做推理，不调子 Agent。
- 独立 Agent 层：Registry + Adapter SPI + CapabilityCatalog + Runtime。
- `capabilityId` 命名空间化，解决 `knowledge.search` 冲突。
- 首期逐个接入 + 声明式注册；自定义 Agent 放到 P5，且必须是受控模板。
- SSE + 轮询降级；主 Agent 自己掌握 deadline/超时/重试。

## 下一步

1. 登记主 Agent 端口与 `wuye_agent_db` 到 `RESOURCES.md`；补 `8402` 登记。
2. 启动物业 `8402` / 合同 `8400`，`WUYE_AGENT_MODE=http` 做活体联调。
3. 接 Manifest 自动同步/版本校验，替换静态能力描述。
4. 接 Python AI 路由（`/internal/v1/ai/route`），规则保留为第一层。
5. 补任务 SSE、审计、Agent 管理后台；确认令牌迁 Redis。
6. 并行推动子 Agent 后续项：异步持久化、usage 计量、tenantId、指标标准化。
