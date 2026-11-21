# 真实 Agent 联调结果（2026-11-21）

> 目标：验证“已完好的业务系统”能否通过 `agent-task-v1` 组装进主 Agent，并跑通 A2A。
> 结论：**能。只读链路全部通过；写操作确认链路通过（用拒绝避免落库）；合同异步审查通过。**

---

## 1. 启动的真实服务

| 服务 | 端口 | 版本/状态 |
|---|---|---|
| 物业 Agent（agent-only profile） | `8402` | `0.9.0`，health/ready UP |
| 合同 Java BFF | `8400` | `0.7.0`，health/ready UP |
| 合同 Python AI | `8500` | health UP |
| 主 Agent（HTTP 模式独立实例） | `8610` | `WUYE_AGENT_MODE=http`，`WUYE_AI_ENABLED=false` |

主 Agent 的 mock 演示 `8600` 未启动，互不影响。

---

## 2. 真实 Agent 契约 smoke

`python3 -I scripts/real_agent_smoke.py`：

```text
PASS  物业 health
PASS  物业 ready
PASS  物业 manifest      版本=0.9.0 能力数=21
PASS  物业 ticket.query  真实返回 agent_gongdan_db 工单
PASS  合同 health
PASS  合同 ready
PASS  合同 manifest      版本=0.7.0 能力数=9
PASS  合同 contract.rule.list

结果：PASS=8 FAIL=0
```

---

## 3. 主 Agent 8610 联调结果

### Agent 目录

```text
contract-review     0.7.0   5 项能力（主 Agent 当前 descriptor）
property-maintenance 0.9.0  21 项能力
```

### 物业只读

输入：

```text
现在还有哪些没处理的工单？
```

结果：

- 路由 `property.ticket.query`；
- 真实返回 `agent_gongdan_db` 中的工单，例如：
  - `GD20261120000125` / `301的门坏了`
  - `GD20261120000124` / `Stage5 契约测试 ...`
  - `GD20261120000123` / `水管漏水报修`

### 物业写操作 + 人工确认

输入：

```text
1栋大堂的灯不亮了，帮我报修
```

结果：

- `WAITING_CONFIRM`；
- 确认卡字段：报修内容、位置、工单类型 `LIGHTING`；
- 选择 **拒绝** → `REJECTED`，**没有向真实物业库写入工单**。

### 合同只读

输入：

```text
合同审查规则有哪些？
```

结果：`contract.rule.list` → `SUCCEEDED`（真实合同库当前规则为空）。

### 合同异步审查

输入：

```text
审查合同 0003
```

结果：

- `contract.review` → `PENDING`；
- 主 Agent 轮询/刷新后 → `SUCCEEDED`；
- 返回真实合同 `contractId=3` 的 diff / findings 等输出。

---

## 4. 联调中发现并修复的问题

| 问题 | 现象 | 修复 |
|---|---|---|
| 物业版本不一致 | 主 Agent/文档写 `0.8.0`，真实 Manifest 是 `0.9.0` | Adapter/Mock/UI/文档统一到 `0.9.0` |
| 合同委托令牌字段不同 | 物业用 `scopes`，合同用 `scope` | `HttpAgentSupport` 同时发送 `scope` 和 `scopes` |
| 合同 tenantId 必须为正 | 默认 `0` 被合同拒绝 | 合同默认 `tenantId=1`，`.env` 增加 `AGENT_CONTRACT_TENANT_ID=1` |
| 合同不接受 `*` scope | 委托签发返回“未声明的能力: *” | `.env` 配置具体能力：`contract.resolve,contract.review,contract.rule.list,contract.rule.create,knowledge.search` |
| 子 Agent 事件缺 `createdAt` | 合同事件落库为 `0000-00-00`，读取 `GET /tasks` 500 | Adapter/TaskService 默认当前时间；MySqlStore 安全读取 zero date |
| `contract.resolve` 返回 `{items:[]}` | 单候选合同 ID 没合并到最终参数 | `mergeOutput` 支持 `items/result` 单元素列表 |

---

## 5. 结论与边界

**已验证：**

- 真实物业 Agent 0.9.0 的 health/ready/manifest/只读任务；
- 主 Agent 通过 HTTP Adapter 调用真实物业，返回真实数据；
- 真实物业写操作产生确认卡，拒绝不落库；
- 真实合同 Agent 0.7.0 的 health/ready/manifest/规则查询/异步审查；
- 主 Agent 的确认、审计、事件、轮询、错误翻译在真实 Agent 上复用。

**边界：**

- 合同规则库当前为空，所以 `contract.rule.list` 返回空数组；
- 合同 1/2 没有版本文件，只有 3 有；审查用 3；
- 没有压测、没有并发/故障注入；
- 没有修改两个子 Agent 的代码/配置/数据；
- 主 Agent mock 演示仍是 8600 默认链路，真实联调是 8610 独立实例。

---

## 6. 停止/重启

停止主 Agent 8610：

```bash
cd ~/Workspace/wuye-agent
bash scripts/stop-prototype.sh
```

停止真实 Agent（由你操作）：

```bash
cd /home/admin1/Workspace/agent-hetong && bash scripts/stop.sh java && bash scripts/stop.sh python
# 物业 agent-only 实例：kill 对应 PID（/tmp/agent-gongdan.pid）
```

重启主 Agent HTTP 联调：

```bash
WUYE_AGENT_MODE=http WUYE_AI_ENABLED=false JAVA_PORT=8610 bash scripts/start-prototype.sh
```
