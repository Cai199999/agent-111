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
- 确认令牌只留在服务端，SSE/API 不返回 token。

## 运行

> 默认端口 `8600` 仍是候选端口。按项目规则，启动前需先登记 `RESOURCES.md`。
> 测试使用随机端口，不占用业务端口。

```bash
# 只跑测试（推荐先跑）
mvn -f apps/java-bff/pom.xml test

# 启动（登记 8600 后）
JAVA_PORT=8600 mvn -f apps/java-bff/pom.xml spring-boot:run

# 或使用脚本
bash scripts/start-prototype.sh
bash scripts/stop-prototype.sh
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

## 关键代码

| 文件 | 作用 |
|---|---|
| `agent/adapter/AgentAdapter.java` | 子 Agent 协议 SPI |
| `agent/adapter/MockPropertyAdapter.java` | 物业方言：`{code,data}`、数字错误码、`REJECTED` |
| `agent/adapter/MockContractAdapter.java` | 合同方言：裸对象、字符串错误码、异步、`CONFIRM_REJECTED` |
| `task/TaskService.java` | 任务状态机、确认、取消、事件去重 |
| `routing/RuleRouter.java` | 规则优先路由，含 `知识库` 消歧 |
| `chat/ChatService.java` | SSE 编排，确认令牌不出服务端 |
| `resources/static/` | 原生单对话框 UI |

## 原型边界

- 无 MySQL/Redis/MinIO；会话与任务在内存，重启即失。
- 无真实登录；使用 `X-Actor-Id` 默认 `10001`。
- 无 Python AI；路由是规则实现，后续替换为 Java → Python `/internal/v1/ai/route`。
- Adapter 是 Mock，不发起真实 HTTP；后续替换为物业 8402 / 合同 8400。
- 任务事件用轮询刷新；后续可加 SSE。
