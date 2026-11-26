# 合同 Agent 0.8.0 接入验证（2026-11-26）

> 范围：主 Agent ↔ 合同 Agent `agent-hetong` 的真实运行态联调。
> 边界：只在主 Agent 侧发起 HTTP 集成测试，不修改合同 Agent 代码/数据/进程。

---

## 1. 环境

| 服务 | 地址 | 状态 |
|---|---|---|
| 合同 Agent Java BFF | `127.0.0.1:8400` | UP |
| 合同 Agent Python AI | `127.0.0.1:8500` | UP，BGE 就绪 |
| 物业 Agent | `127.0.0.1:8200` | UP |
| 主 Agent Java BFF | `127.0.0.1:8600` | UP（mysql + http + AI） |
| 主 Agent Python AI | `127.0.0.1:8700` | UP |

合同 Manifest 同步结果：`version=0.8.0`、能力 9、Skill 6；Agent Card 展示 6 个启用能力、6 个 Skill。

## 2. 主 Agent 侧修复

1. **委托租户对齐**
   - 原问题：委托签发请求 `X-Tenant-Id` 取登录用户租户 `0`，委托 claims 取合同端点租户 `1`，
     合同 Agent 返回 `TENANT_MISMATCH`，导致审查任务被判 AUTH。
   - 修复：子 Agent 端点登记的租户优先，并同时用于请求头与委托 claims。
2. **合同按名称审查的计划优先**
   - 规则已有 `contract.resolve → contract.review` 计划时，不被 LLM 的“缺 contractId”追问覆盖。
3. **合同名称查询清洗**
   - `帮我审一下物业服务合同` → resolve 查询 `物业服务合同`，去掉“帮我/审一下”等指令词。
4. **委托链路可观测**
   - 委托签发失败/成功、缺失均记录日志，便于定位 `TENANT_MISMATCH` 等问题。

## 3. 验证结果

### 3.1 真实 Agent smoke

`AGENT_PROPERTY_BASE_URL=http://127.0.0.1:8200 python3 -I scripts/real_agent_smoke.py`

- 物业 health / ready / manifest / `ticket.query`：4/4 PASS
- 合同 health / ready / manifest / `contract.rule.list`：4/4 PASS
- **合计 8/8 PASS**

### 3.2 主 Agent 合同链路

| 项目 | 结果 |
|---|---|
| Agent Card | `0.8.0`，6 个启用能力，6 个 Skill |
| `contract.rule.list` | 任务 `SUCCEEDED`，但 `rules=[]` |
| `contract.resolve("物业服务合同")` | `SUCCEEDED`，10 个候选 |
| `审查合同 <contractId>` | `SUCCEEDED`，`skillId=contract.review`，`findingsCount=10` |

> `contract.rule.list` 为空：合同 0.8.0 很可能只向 Agent 暴露已发布规则，而种子规则仍是
> `DRAFT_UNREVIEWED`。需要合同 Agent 侧确认是否应给 ADMIN 返回草稿，或先发布规则。

### 3.3 全量 E2E（真实模式）

`E2E_REAL=1 BASE_URL=http://127.0.0.1:8600 python3 -I scripts/e2e_full.py`

- **PASS=96 / FAIL=5**
- 失败项：
  - `合同规则返回`：E2E 期望 `R-CON-001`，但真实 `rules=[]`；
  - `合同审查最终成功` / `合同审查风险数`：E2E 用名称“物业服务合同”，实际有 10 个同名候选，
    主 Agent 正确地要求指定 contractId，未自动猜；
  - `合同审查可取消`：真实审查已完成，取消不适用；
  - `巡查自动带出地块`：GIS 检查，不属于合同链路。
- 手工用明确 `contractId` 审查已 `SUCCEEDED` 且产出 10 条 Finding，证明合同集成链路可用。

## 4. 结论与待办

**合同 Agent 0.8.0 已可被主 Agent 正常调用**：Manifest/Card/Skill、委托签发、合同解析、
审查、Finding 返回均验证通过。

仍需合同 Agent 侧或测试侧处理：

1. 确认 `contract.rule.list` 是否应返回 DRAFT 规则；否则先发布种子规则，E2E 才能验证规则内容。
2. 真实 E2E 合同段改用显式 `contractId`（或先 resolve 再选择），名称同名时不应自动猜。
3. 合同取消检查需要长任务或明确“已终态不可取消”的预期。
4. 合同 Agent 内部测试套件仍由合同项目自行执行；本次只在主 Agent 侧做集成验证。
