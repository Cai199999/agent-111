# 可扩展多 Agent 演示：第三个 Agent 是怎么接进来的

> 这段演示要证明的不是“我又写了一个 GIS 功能”，而是：
> **新增一个领域 Agent，不需要改主 Agent 的路由、确认、审计、人工队列和看板。**

---

## 一、三个 Agent 的分工

| Agent | 领域 | 能力 | 接入方式 |
|---|---|---|---|
| `property-maintenance` | 物业运维 | 工单查询/建单/派单/知识 | Mock / HTTP 方言 |
| `contract-review` | 合同审查 | 规则/解析/审查 | Mock / HTTP 方言 |
| `geo-asset-agent` | 时空资产（GIS/遥感演示） | 地块查询/变化图斑/巡查任务 | Mock（新增） |

新增的 `geo-asset-agent` 只做三件事：

1. 查询地块：`gis.parcel.query`
2. 查询遥感变化图斑：`gis.change.query`
3. 创建巡查任务：`gis.patrol.create`（写操作，需确认）

---

## 二、接入一个 Agent 只做三步

### 第 1 步：实现 `AgentAdapter` + 描述能力

```java
@Component
@ConditionalOnProperty(name = "wuye.agent.mode", havingValue = "mock", matchIfMissing = true)
public class MockGisAdapter implements AgentAdapter {
    private final AgentDescriptor descriptor = new AgentDescriptor(
        "geo-asset-agent", "时空资产 Agent（Mock）", "gis-v01", "0.1.0", "raw",
        "/health", "/ready", true, "none",
        List.of(
            capability("gis.parcel.query", "parcel.query", "查询地块", true, false),
            capability("gis.change.query", "change.query", "查询遥感变化图斑", true, false),
            capability("gis.patrol.create", "patrol.create", "创建巡查任务", false, true)
        ));
    // submit / getTask / confirm / cancel / events / health
}
```

### 第 2 步：注册为 Spring Bean

`AdapterRegistry` 会自动发现所有 `AgentAdapter` Bean：

```java
@Component
public class AdapterRegistry {
    public AdapterRegistry(List<AgentAdapter> discovered) { ... }
}
```

**没有改 `AdapterRegistry`，没有改 `ChatService`，没有改 `TaskService`。**

### 第 3 步：补权限数据

新增 `V10__gis_agent.sql`：

```sql
INSERT IGNORE INTO wa_permission (permission_code, description) VALUES
    ('agent:geo-asset-agent:use', '使用时空资产 Agent'),
    ('capability:gis.parcel.query', '查询地块'),
    ('capability:gis.change.query', '查询遥感变化图斑'),
    ('capability:gis.patrol.create', '创建巡查任务');

INSERT IGNORE INTO wa_role_permission (role_code, permission_code) VALUES
    ('BASE', 'agent:geo-asset-agent:use'),
    ('BASE', 'capability:gis.parcel.query'),
    ('BASE', 'capability:gis.change.query'),
    ('WORKER', 'capability:gis.patrol.create');
```

`PolicyService` 对新 Agent 走约定：

```java
default -> user.isAdmin() || user.hasPermission("agent:" + agentKey + ":use");
```

---

## 三、主 Agent 自动复用了什么

| 能力 | 是否改主流程 | 说明 |
|---|---|---|
| Agent 目录/权限裁剪 | 否 | `AgentCatalogService` 自动读取新 descriptor |
| LLM 路由 | 否 | capability 列表自动进入 LLM prompt |
| 规则兜底 | 少量 | `RuleRouter` 加“地块/图斑/巡查”关键词，避免 LLM 不可用时不可演示 |
| Schema 校验 | 少量 | `CapabilitySchemaService` 加 GIS fallback schema |
| 任务状态机 | 否 | `TaskService` 不感知领域 |
| 人工确认 | 否 | `gis.patrol.create` 声明 `requiresConfirmation=true` 即生效 |
| 审计 | 否 | `TASK_SUBMIT/TASK_CONFIRM` 自动记录 |
| 人工介入队列 | 否 | 失败/拒绝/待确认自动入队 |
| 数据看板 | 否 | `byAgent/byCapability` 自动出现 `geo-asset-agent` |
| Agent Card | 否 | 主 Card 的 `subAgents` 和子 Card 自动包含 |
| SSE/任务时间线 | 否 | 复用同一套事件模型 |
| 记忆/画像 | 否 | 复用主 Agent 的会话摘要/全局画像 |

**一句话：**
> “新增 Agent 的成本集中在‘领域适配’，不是‘平台改造’。”

---

## 四、完整演示链路（建议 3–4 分钟）

### 1. 登录后看 Agent 目录

- 侧边栏出现三个 Agent：物业、合同、时空资产；
- 右上角 A2A 能力 → 主 Card 的 `subAgents` 包含 `geo-asset-agent`；
- 子 Agent Card 显示 `gis-v01`、三个能力和 `dialect-adapter`。

### 2. 地块查询

输入：

> 江北新区 A-01 那个商业地块现在什么状态？

预期：

- 路由到 `geo-asset-agent / gis.parcel.query`；
- 按名称/编号唯一命中江北新区 A-01 地块；
- 返回 `P-2026-001`，中文解释面积、用途、登记状态；
- 演示时不需要输入地块编码。

### 3. 遥感变化图斑

输入：

> 最近有没有比较紧急的遥感变化图斑？

预期：

- 路由到 `gis.change.query`；
- “紧急”自动映射 `level=HIGH`；
- 返回 2 个高等级图斑：新增建设、疑似违建；
- 中文解释面积和复核状态。

### 4. 巡查任务（写操作）

输入：

> 安排人去江北 A-01 地块做一次变化图斑复核

预期：

- 路由到 `gis.patrol.create`；
- 主 Agent 先按名称解析出 `P-2026-001`，不需要用户输入编码；
- 弹出高风险确认卡：地块、巡查类型、截止时间；
- 确认后返回 `PT-2026-001 / CREATED`；
- 审计日志出现 `TASK_SUBMIT / TASK_CONFIRM`；
- 数据看板的 `byAgent` 出现 `geo-asset-agent`。

### 5. 收尾话术

> “第三个 Agent 没有改主 Agent 的编排、确认、审计、人工队列和看板。新增领域 Agent 的成本是写一个 Adapter、补一份权限数据、加几个领域关键词；平台能力是复用的。这就是可扩展的多 Agent 平台。”

---

## 五、边界与下一步

### 现在

- `geo-asset-agent` 是 Mock，不接真实 GIS 引擎；
- 没有地图渲染/空间分析/缓冲区/叠加分析能力；
- 没有真实遥感影像/图斑入库；
- 规则路由只覆盖几个演示关键词。

### 下一步

- 用 `AbstractHttpAdapter` 接真实 GIS Agent（HTTP 方言）；
- 新增能力：`gis.buffer.analyze`、`gis.overlay.analyze`、`gis.route.plan`、`rs.change.detect`；
- 写操作继续复用人工确认/审计/人工队列；
- 前端加简单 SVG/GeoJSON 预览（不依赖外网地图瓦片）。

**面试话术：**
> “我没有在面试前接真实 GIS 引擎，因为那不是这次要证明的。我要证明的是：平台能低成本接入第三个、第四个领域 Agent，而且治理能力自动复用。真实 GIS 引擎通过 HTTP Adapter 接进来即可，主流程不用改。”

---

## 六、高频追问

**Q：新增 Agent 要改哪些代码？**
> 一个 Adapter + 一份权限数据；可选加规则关键词和前端字段标签。核心 `ChatService/TaskService/Router/审计/人工队列/看板` 不改。

**Q：怎么保证新 Agent 不影响其他 Agent？**
> capabilityId 命名空间化（`gis.*`）、权限隔离、独立 Agent 健康、独立确认策略；失败只影响当前任务，主流程降级/转人工。

**Q：为什么用 Mock？**
> 演示稳定、可控、不依赖外部 GIS 服务；真实接入走 `AbstractHttpAdapter`，协议和确认/事件模型不变。

**Q：这算多 Agent 平台还是多 Agent Demo？**
> 是“可扩展的多 Agent 原型”：有注册、发现、路由、确认、审计、人工兜底、Agent Card、失败自修复；还不是生产级多实例平台。边界要说清楚。
