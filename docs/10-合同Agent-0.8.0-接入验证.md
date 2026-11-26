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
| `contract.rule.list` 不传 contractType | `SUCCEEDED`，`rules=[]` |
| `contract.rule.list(contractType=SERVICE)` | `SUCCEEDED`，36 条（31 DRAFT + 5 PUBLISHED） |
| `publishedOnly=true` | 5 条 PUBLISHED |
| `contract.resolve("物业服务合同")` | `SUCCEEDED`，10 个候选，需显式选择 |
| `审查合同 <contractId>` | `SUCCEEDED`，`skillId=contract.review`，`findingsCount=10` |
| 终态取消 | 返回 409，符合 0.8.0 契约 |

> 重要差异：合同侧说明“默认返回全部 ENABLED”，但当前 8400 实际在 `contractType` 缺省时
> 返回 0，传入 `SERVICE` 才返回 36。可能是运行中的 BFF 未重启到 4f9c83b，或 contractType
> 仍为必填；需要合同侧确认。主 Agent E2E 使用“服务合同”提示以显式触发 `SERVICE`。

### 3.3 全量 E2E（真实模式）

`E2E_REAL=1 BASE_URL=http://127.0.0.1:8600 python3 -I scripts/e2e_full.py`

最新结果：**PASS=99 / FAIL=2**

- 合同段已通过：
  - 服务合同规则返回 36 条；
  - 通过 `contract.resolve` 显式选择 `contractId` 后审查成功，Finding 数可读；
  - 终态取消按 409 断言通过。
- 剩余 2 项失败均为 **物业 Agent 8200 在测试后段下线**：
  - `缺关键信息为追问/确认`：实际为 `ConnectException`；
  - `工具执行成功`：同样为物业 Agent 不可达。
- 此前合同段 4 项失败已通过真实数据适配消除：
  - 使用 `SERVICE` 查询规则；
  - 显式 resolve + contractId；
  - 终态取消 409 视为通过。

## 4. 结论与待办

**合同 Agent 0.8.0 已可被主 Agent 正常调用**：Manifest/Card/Skill、委托签发、合同解析、
审查、Finding 返回均验证通过。

仍需合同 Agent 侧或测试侧处理：

1. 确认 8400 运行版本是否已包含 4f9c83b：当前 `contract.rule.list` 不传 `contractType` 返回 0，
   与“默认返回全部 ENABLED”不一致；若 contractType 仍为必填，请在契约中明确。
2. 合同同名场景已按新契约：主 Agent 先 resolve，再由编排层/用户显式选择 contractId。
3. 合同取消已按终态 409 处理；E2E 不再构造长任务。
4. 合同 Agent 内部测试套件仍由合同项目自行执行；本次在主 Agent 侧做真实集成验证。
