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
| [`docs/memory/00_project_brief.md`](docs/memory/00_project_brief.md) | 项目 brief 与当前状态 |

## 输入依据

- [`PropertyMaintenanceAgent-主Agent接入使用说明.md`](PropertyMaintenanceAgent-主Agent接入使用说明.md)
- [`使用手册.md`](使用手册.md)

## 原型（已可运行）

位置：`apps/java-bff/`。纵向切片：Java BFF + MySQL 持久化 + Mock/真实 HTTP Adapter + 原生单对话框 UI。

```bash
cp .env.example .env && chmod 600 .env

# 测试（内存 profile，随机端口，不占业务端口）
mvn -f apps/java-bff/pom.xml test
# 当前：Java 31/31 passed；Python AI 9/9

# 启动（8600 需先登记 RESOURCES.md；脚本会 source .env）
bash scripts/start-prototype.sh
```

已实现：登录 + JWT + 三级 RBAC（用户/工人/管理员，权限表驱动）、个人中心动态表单（表单由子 Agent Manifest 提供）、按权限过滤 Agent/能力卡片；单对话框、SSE、LLM 主导路由（DeepSeek/OpenAI 兼容）、计划拆解（只读前置步骤 + 最终写操作）、**多轮补参状态**、**主数据枚举映射（ticketTypes/priorities 等）**、子 Agent 校验失败回传 LLM 自动修正参数、规则仅作 LLM 不可用时兜底；**子 Agent 原始 output 保留在任务 API/事件中，聊天区按中文字段名与中文枚举展示，并附 LLM 自然语言解释（不改写数据）**；**子 Agent 能力以工具形式暴露**（`GET /api/v1/tools`、`POST /api/v1/tools/{name}/execute`）；**ADMIN 数据看板**（任务总数/成功率/待确认/平均耗时/人工介入率/工单漏斗/能力分布）；写操作确认/拒绝、异步合同审查、取消、任务事件时间线、确认令牌不出服务端；会话删除（级联删除消息/任务/事件）；会话/消息/任务/事件落 MySQL 并跨重启保留；`WUYE_AGENT_MODE=http` 接真实物业 8402 / 合同 8400。

演示账号：`admin/admin123`、`worker/worker123`、`user/user123`。合同 Agent 仅管理员可见；普通用户只能建单/查自己的工单；工人可接单/处理/解决，不能建单；管理员全部。

```bash
# 接入真实大模型
# 1) 在 .env 填 DEEPSEEK_API_KEY
# 2) 启动 Python AI（默认 8700，需登记 RESOURCES.md）
bash scripts/start-python-ai.sh
# 3) 打开 LLM 路由并重启 Java
WUYE_AI_ENABLED=true bash scripts/start-prototype.sh
```

原型边界：无真实登录、Manifest 静态、无 Redis/MinIO、任务事件轮询、真实子 Agent 未启动尚未联调。

详见 [`apps/java-bff/README.md`](apps/java-bff/README.md)。

## 当前状态

- 设计文档已同步到子 Agent 最新契约：物业 `0.8.0`、合同 `0.7.0`。
- 子 Agent P0 整改已落地：委托令牌签发、Manifest 元数据、健康/就绪、`clientTaskId` 回查、取消、事件 `seq/afterSeq`。
- 两个子 Agent 当前均未启动；主 Agent 端口/库/前缀/bucket 待登记确认。
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
