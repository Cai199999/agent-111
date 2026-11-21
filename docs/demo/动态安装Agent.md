# 动态安装 Agent：可扩展性证明 vs 完整 Agent

> 面试时把两件事分开讲：
> 1. **可扩展性证明**：内置第三个 Agent（时空资产）证明“新增 Agent 不改主流程”；
> 2. **完整 Agent 能力**：运行时安装一个“巡检报告 Agent”，覆盖只读、写操作确认、异步任务、事件、取消、导出。
>
> 这样既有“平台可扩展”的证据，也有“单个 Agent 能力完整”的证据。

---

## 一、两个演示对象

| | 可扩展性证明 | 完整 Agent |
|---|---|---|
| Agent | `geo-asset-agent`（内置 Mock） | `inspection-agent`（运行时安装） |
| 能力 | 地块查询、变化图斑、巡查任务 | 巡检报告查询、巡检计划、报告生成（异步）、报告导出（异步） |
| 写入 | 巡查任务，需确认 | 巡检计划/报告生成，需确认 |
| 异步 | 无 | 报告生成/导出，带进度事件 |
| 接入方式 | Spring Bean + 权限迁移 | 管理员点击安装，动态注册 |
| 证明 | 新增 Agent 只做领域适配 | 运行时安装 + 完整生命周期 |

---

## 二、安装入口

侧边栏「Agent 状态」右侧有一个 `＋`：

```text
Agent 状态   ＋
● 物业 0.8.0
● 合同 0.7.0
● 时空资产 0.1.0
```

点开后有两种安装方式：

### 方式 A：快速安装完整示例 Agent

- 按钮：「安装巡检报告 Agent」
- 后端：`POST /api/v1/admin/agents/install` `{"template":"inspection"}`
- 结果：`inspection-agent` 立即注册，侧边栏多一个 Agent。

### 卸载

运行时安装的 Agent 在“Agent 状态”里会带一个 `×`：

- 只有 ADMIN 可见；
- 点击后二次确认；
- 调用 `DELETE /api/v1/admin/agents/{agentKey}`；
- 卸载后该 Agent 的能力立即从路由/目录/Agent Card 消失；
- 内置 Agent（物业/合同/时空资产）没有 `×`，不允许卸载。

### 方式 B：从 Agent Card URL 安装

- 输入：外部 Agent 的 Agent Card URL，例如 `http://127.0.0.1:8900`
- 可选：Internal Token
- 后端：`POST /api/v1/admin/agents/install` `{"cardUrl":"...","internalToken":"..."}`
- 平台会：
  1. 拉取 `/.well-known/agent-card.json`；
  2. 校验 `agentId/version/capabilities`；
  3. 健康检查 `/health`、`/ready`；
  4. 动态创建 Canonical v1 HTTP Adapter；
  5. 注册到 `AdapterRegistry`。

> URL 安装只接受 HTTP 契约，不允许上传 jar/脚本；管理员权限；禁止云元数据地址。

---

## 三、完整 Agent 的能力

`inspection-agent` 是运行时安装的完整示例：

| 能力 | 类型 | 行为 |
|---|---|---|
| `inspect.report.query` | 只读 | 查询 3 份巡检报告 |
| `inspect.plan.create` | 写操作 | 弹确认卡：区域/计划名称/截止时间 |
| `inspect.report.generate` | 写操作 + 异步 | 确认后 `PENDING → RUNNING → SUCCEEDED`，返回报告号 |
| `inspect.report.export` | 只读 + 异步 | 返回导出文件、记录数 |

覆盖：

- 人工确认；
- 异步任务与进度事件；
- 取消；
- 任务时间线；
- 审计；
- 人工介入队列；
- 看板 `byAgent/byCapability`；
- Agent Card 自动出现。

---

## 三、企业 Agent 示例市场（真实 HTTP 契约）

安装弹窗里还有两个“企业 Agent 示例”，通过 Agent Card URL 安装：

| Agent | URL | 能力 |
|---|---|---|
| 政策法规问答 Agent | `http://127.0.0.1:8600/demo-agents/policy` | 政策问答、政策文件审查（确认） |
| 客服工单 Agent | `http://127.0.0.1:8600/demo-agents/customer` | 客服工单查询、创建（确认） |

它们不是本地 Adapter 直连，而是真实的 HTTP Canonical v1：

- `/.well-known/agent-card.json`
- `/health`、`/ready`
- `/agent/v1/tasks`、`/confirm`、`/cancel`、`/events`

它们同样复用主 Agent 的：

- 人工确认；
- 审计；
- 人工介入；
- 看板；
- 记忆注入。

### 记忆怎么体现

主 Agent 在调用动态 Agent 前，把当前会话摘要和全局画像放进 `_memory`：

```json
{
  "input": {
    "question": "耕地保护",
    "_memory": {
      "sessionSummary": "...",
      "userProfile": "{\"preferredTicketTypeCode\":\"LIGHTING\",...}"
    }
  }
}
```

动态 Agent 返回：

```json
{
  "memoryUsed": true,
  "memoryHint": "已结合主 Agent 注入的会话摘要/用户画像"
}
```

**记忆是主 Agent 层的能力**，不是每个子 Agent 各建一套记忆库。

---

## 四、安装后发生了什么

```text
POST /api/v1/admin/agents/install
  → AgentInstallService
      → 创建 DynamicInspectionAgent / DynamicHttpAgentAdapter
      → AdapterRegistry.register(...)
      → DynamicAgentRegistry.markInstalled(...)
  → /api/v1/agents 自动多一个 Agent
  → Agent Card 自动多一个子 Agent
  → LLM 路由自动看到新能力
  → 确认/审计/人工队列/看板自动复用
```

**没有改**：

- `ChatService`
- `TaskService`
- `TaskController`
- `AuditService` / `HumanInterventionService`
- `MetricsService`
- `AgentCardController`（它读的是 Registry）

**改了什么**：

- `AdapterRegistry` 增加 `register/unregister`；
- 新增 `AgentInstallService`；
- 新增 `DynamicInspectionAgent`（完整示例）；
- 新增 `DynamicHttpAgentAdapter`（外部 URL 安装）；
- `PolicyService` 对动态能力给管理员临时授权；
- 前端 `＋` 和安装弹窗。

---

## 五、演示链路（3 分钟）

### 1. 看现状

- 侧边栏 3 个 Agent：物业、合同、时空资产；
- A2A 能力 → 3 个子 Agent Card。

### 2. 安装完整 Agent

- 点「Agent 状态」旁的 `＋`；
- 点「安装巡检报告 Agent」；
- 侧边栏立即变成 4 个 Agent；
- A2A 能力里出现 `inspection-agent`。

话术：

> “这个 Agent 不是启动时写死的 Spring Bean，是运行时安装的；主流程没有重启、没有改代码。”

### 3. 查询巡检报告

> 查询巡检报告

预期：返回 3 份报告（IR-2026-001/002/003），中文解释。

### 4. 生成巡检报告（完整生命周期）

> 生成今天的巡检报告

预期：

- 路由到 `inspect.report.generate`；
- 高风险确认卡：报告类型/区域/统计周期；
- 确认后 `PENDING → RUNNING(30%) → RUNNING(70%) → SUCCEEDED`；
- 返回 `IR-2026-001`。

讲解点：

> “新装的 Agent 自动继承了人工确认、异步任务、事件时间线、审计和人工兜底。这就是‘完整 Agent 能力’，不是只能查数据的玩具。”

### 5. 导出（可选）

> 导出巡检报告

预期：异步 `PENDING → SUCCEEDED`，返回文件名和记录数。

### 6. 卸载（可选）

```bash
curl -X DELETE http://127.0.0.1:8600/api/v1/admin/agents/inspection-agent \
  -H "Authorization: Bearer $TOKEN"
```

预期：能力从目录消失，其他 Agent 不受影响。

---

## 六、边界与安全

### 现在

- 动态安装是**内存态**，重启后消失；
- URL 安装只支持 Canonical v1 HTTP 契约；
- 只做了基础 SSRF 防护（禁止云元数据地址）；
- 动态能力默认仅管理员可用；
- 没有安装审批、签名、版本兼容校验。

### 生产化

- 落 `wa_agent_registry` / `wa_agent_capability`；
- 安装审批 + 权限分配；
- URL allowlist / 私网策略；
- Agent Card 签名 / 版本兼容；
- 安装/启用/卸载审计；
- 灰度与回滚。

**面试话术：**
> “这是受控安装的演示版：管理员点按钮安装，平台拉 Agent Card、健康检查、动态注册；不执行任意代码。生产化还要补持久化、审批、SSRF allowlist 和版本兼容。这套渐进路径不会锁死架构。”

---

## 七、面试问答

**Q：这算任意安装吗？**
> 不是任意代码执行，是受控安装：只接受 Agent Card/HTTP 契约，管理员权限，健康检查后才注册。

**Q：为什么区分“可扩展性证明”和“完整 Agent”？**
> 时空资产 Agent 证明新增 Agent 的低成本；巡检报告 Agent 证明新 Agent 可以具备完整生命周期（确认、异步、事件、取消、审计）。两个证据分开讲，避免“只会加只读 Agent”的质疑。

**Q：安装后主流程改了吗？**
> 没有。Registry 动态注册，路由/确认/审计/人工队列/看板都是读 Registry 的，自动复用。

**Q：重启后还在吗？**
> 当前是内存态，重启消失；这是演示版的边界。生产化落库 + 启动恢复即可。
