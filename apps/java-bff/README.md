# wuye-agent Java BFF 原型

单对话框主 Agent 的纵向切片：

```text
浏览器（原生 HTML/JS/CSS）
  → Java BFF（Spring Boot）
      → RuleRouter（规则路由）
      → AgentAdapter SPI
          → MockPropertyAdapter（property-v08 方言）
          → MockContractAdapter（contract-v07 方言）
      → TaskService（任务/确认/取消/事件）
      → 内存存储
```

## 当前能力

- 单对话框：会话列表、消息历史、SSE 流式输出、路由标识；
- 两域路由：物业工单 / 合同规则 / 两域知识检索；
- 消歧：`查一下知识库` 会追问物业还是合同；
- 写操作确认：建单、派单、创建规则 → `WAITING_CONFIRM`；
- 确认同意 / 拒绝：拒绝统一为 `REJECTED`；
- 异步任务：合同审查 `PENDING → RUNNING → SUCCEEDED`；
- 取消：物业/合同均支持 `CANCELLED`；
- 任务卡片：状态、进度、事件时间线、确认摘要；
- 确认令牌只留在服务端，SSE/API 不返回 token；
- 持久化：MySQL `wuye_agent_db` + Flyway，会话/消息/任务/事件跨重启保留；
- 真实 HTTP Adapter：`WUYE_AGENT_MODE=http` 时接物业 8402 / 合同 8400，支持方言、200/202、委托令牌、错误映射。

## 运行

> 默认端口 `8600` 仍是候选端口。按项目规则，启动前需先登记 `RESOURCES.md`。
> 测试使用随机端口，不占用业务端口。

```bash
# 1. 准备配置（真实值不要提交）
cp .env.example .env && chmod 600 .env

# 2. 只跑测试（内存 profile，不需要 MySQL）
mvn -f apps/java-bff/pom.xml test
# 当前：14/14 passed

# 3. 启动（脚本会 source .env；默认 mysql profile）
bash scripts/start-prototype.sh
bash scripts/stop-prototype.sh
```

手动启动：

```bash
set -a; source .env; set +a
# MySQL + Mock Adapter
mvn -f apps/java-bff/pom.xml spring-boot:run
# 纯内存（不连 MySQL）
SPRING_PROFILES_ACTIVE=memory mvn -f apps/java-bff/pom.xml spring-boot:run
# 真实 HTTP Adapter
WUYE_AGENT_MODE=http mvn -f apps/java-bff/pom.xml spring-boot:run
```

打开 `http://127.0.0.1:8600/`。

## 快速体验

| 输入 | 期望 |
|---|---|
| `我有哪些待处理工单？` | 路由 `property.ticket.query`，返回 3 张工单 |
| `查一下 GD20261119000107 详情` | 路由 `property.ticket.detail` |
| `把 GD20261119000107 派给 10002` | `WAITING_CONFIRM`，确认后成功 |
| `报修：3栋2单元水管漏水` | 建单确认，拒绝后 `REJECTED` |
| `合同审查规则有哪些？` | 路由 `contract.rule.list` |
| `审查合同 10001` | 异步任务，1.5s 后成功 |
| `查一下知识库` | 追问物业/合同域 |
| `合同创建规则：付款节点必须明确` | 确认后异步创建规则 |

## 持久化（mysql profile）

- 库：`wuye_agent_db`；账号 `wuye_agent_app`（仅本库 DML/DDL）。
- Flyway：`src/main/resources/db/migration/V1__chat.sql`、`V2__task.sql`。
- 表：`wa_chat_session`、`wa_chat_message`、`wa_task_binding`、`wa_task_event`。
- 确认令牌：AES-256-GCM 加密后存 `wa_task_binding.confirmation_token_cipher`，不落明文。
- 已验证：创建会话/派单/确认后重启进程，会话消息、任务状态、事件均从 MySQL 恢复。
- 纯内存：`SPRING_PROFILES_ACTIVE=memory`，用于无数据库环境。

## 真实 HTTP Adapter

```bash
export WUYE_AGENT_MODE=http
export AGENT_PROPERTY_BASE_URL=http://127.0.0.1:8402
export AGENT_PROPERTY_INTERNAL_TOKEN=...
export AGENT_CONTRACT_BASE_URL=http://127.0.0.1:8400
export AGENT_CONTRACT_INTERNAL_TOKEN=...
```

- `HttpPropertyAdapter`：处理 `{code,data}`、数字错误码、`/agent/v1/health`。
- `HttpContractAdapter`：处理裸对象、字符串错误码、`202`、`/health`、`/ready`。
- 委托令牌：调用各 Agent 的 `POST /agent/v1/delegation/token`，按 `audience` 缓存；签发失败回退到内部令牌 + Actor 头。
- 任务事件：任务详情没有事件时，自动补拉 `/agent/v1/tasks/{id}/events`。
- 子 Agent 停机：路由仍可用，任务返回 `FAILED(DEPENDENCY)`，前端提示“调用子 Agent 失败”，不崩溃。
- 当前子 Agent 未启动，真实联调待 8402/8400 启动后进行；Manifest 目前为静态描述，后续接 Manifest 同步。

## 关键代码

| 文件 | 作用 |
|---|---|
| `agent/adapter/AgentAdapter.java` | 子 Agent 协议 SPI |
| `agent/adapter/MockPropertyAdapter.java` | 物业方言：`{code,data}`、数字错误码、`REJECTED` |
| `agent/adapter/MockContractAdapter.java` | 合同方言：裸对象、字符串错误码、异步、`CONFIRM_REJECTED` |
| `task/TaskService.java` | 任务状态机、确认、取消、事件去重 |
| `store/Store.java` | 存储抽象；内存/MySQL 两实现 |
| `store/MySqlStore.java` | MySQL 持久化、令牌加解密、事件落库 |
| `persistence/MysqlPersistenceConfig.java` | 手工 DataSource/Flyway 装配 |
| `agent/http/HttpAgentSupport.java` | 真实 HTTP 请求、委托令牌缓存 |
| `agent/http/AbstractHttpAdapter.java` | 两种方言的提交/查询/确认/取消/事件/健康 |
| `routing/RuleRouter.java` | 规则优先路由，含 `知识库` 消歧 |
| `chat/ChatService.java` | SSE 编排，确认令牌不出服务端 |
| `resources/static/` | 原生单对话框 UI |

## 与设计文档的差异（原型取舍）

| 项 | 设计文档 | 原型现状 | 原因 |
|---|---|---|---|
| 持久化实现 | MyBatis-Plus + 实体/Mapper | JdbcTemplate + `Store` 接口 | 避开自动配置冲突，快速验证；接口不变，后续可替换 |
| 确认令牌 | Redis + TTL | MySQL AES-GCM 加密列 | 不引入 Redis 依赖；生产建议迁回 Redis |
| 表范围 | 16 张完整表 | 4 张核心表 | 先覆盖会话/消息/任务/事件；管理/审计表后续 |
| Manifest | 自动同步/版本校验 | 静态描述 | 子 Agent 未启动；后续接同步 |
| 路由 | 规则 + Python LLM | 规则 | Python AI 服务尚未搭建 |

## 原型边界

- 无真实登录；使用 `X-Actor-Id` 默认 `10001`。
- 无 Python AI；路由是规则实现，后续替换为 Java → Python `/internal/v1/ai/route`。
- Manifest 为静态描述，尚未接自动同步/版本校验。
- 无 Redis/MinIO；确认令牌用 MySQL 加密列，附件上传未接。
- 任务事件用轮询刷新；后续可加任务 SSE。
- 真实 HTTP Adapter 已实现，但两个子 Agent 当前未启动，尚未完成活体联调。
- 未接 Agent 管理后台的注册/同步/能力开关；当前为代码内注册。
