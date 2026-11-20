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
4. 能力目录与路由（规则优先、LLM 兜底、消歧）
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
- 主 Agent 原型：Java BFF + 内存存储 + Mock Adapter + 单对话框，优先跑通只读/确认/取消链路。

## 关键决策

- Java BFF + Python AI；Python 只做推理，不调子 Agent。
- 独立 Agent 层：Registry + Adapter SPI + CapabilityCatalog + Runtime。
- `capabilityId` 命名空间化，解决 `knowledge.search` 冲突。
- 首期逐个接入 + 声明式注册；自定义 Agent 放到 P5，且必须是受控模板。
- SSE + 轮询降级；主 Agent 自己掌握 deadline/超时/重试。

## 下一步

1. 评审 `docs/01`–`06`。
2. 登记主 Agent 端口/库/前缀/bucket（需用户确认）。
3. 编写 Mock Agent 与内部契约代码（Adapter SPI、统一模型）。
4. 只读 MVP：Manifest 同步 → 能力目录 → 规则/LLM 路由 → 统一任务卡片。
5. 并行推动子 Agent 三个 P0：合同委托令牌签发、两边 `inputSchema`、物业端口改造。
