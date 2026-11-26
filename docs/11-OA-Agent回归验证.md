# OA 子 Agent 回归验证（2026-11-26）

> 范围：OA `flow-agent` 1.0.0 的自身测试 + 主 Agent → OA 真实集成链路。
> 边界：只修复 OA 的确认过期时间和测试夹具，不改 OA 业务规则。

---

## 1. 运行态

| 服务 | 地址 | 状态 |
|---|---|---|
| OA `flow-agent` | `127.0.0.1:8810` | UP |
| 主 Agent | `127.0.0.1:8600` | UP |
| 主 Agent 注册表 | `oa-entry-agent` | `1.1.0`，18 能力，12 Skill |

主 Agent 健康检查：`health=UP`、`ready=UP`，动态 Agent 调用正常。

> OA 1.1.0 已声明 12 个任务级 Skills；主 Agent 的 LLM/自动解析可直接选择
> `oa.leave`、`oa.meeting` 等 Skill，旧的无 `skillId` 调用仍兼容。

## 2. OA 自身测试

- `pytest`：**162/162 PASS**
- 修复 1：会议测试夹具原固定 `2026-11-25`，相对当前日期已过期，
  被“过去时间”规则拦截；改为 `case.now + 1 天`。
- 修复 2：MySQL 读出的 `expires_at` 为 naive datetime，
  与 aware UTC 比较触发 `TypeError`，导致主 Agent 读取确认任务时 OA 返回 500；
  已统一按 UTC 处理，并新增 naive future/past 两个断言。

## 3. 主 Agent → OA 真实链路

| 场景 | 结果 |
|---|---|
| 会议室预约草稿 | `SUCCEEDED`，`submittable=true`，审批链正常 |
| 请假草稿 | `SUCCEEDED`，`skillId=oa.leave`，`submittable=true`，审批链正常 |
| 提交请假单 | `WAITING_CONFIRM`，`skillId=oa.leave`，确认卡正常 |
| 确认提交 | `SUCCEEDED`，生成 `LV-20261126-0002` |
| 主平台真实 E2E | **101/101**（OA Skills 生效后无回归） |

请求示例：

```text
下周三请两天年假回老家
提交请假单 DRF-...
```

## 4. OA 1.1.0 Skills

- Manifest / Agent Card 升级 `1.1.0`，声明 12 个 Skills：
  `oa.leave`、`oa.matter`、`oa.meeting`、`oa.seal`、`oa.purchase`、`oa.notice`、
  `oa.archive`、`oa.workflow`、`oa.profile`、`oa.knowledge`、`oa.directory`、`oa.dashboard`。
- 任务请求新增 `skillId`：
  - 未知 Skill → 404 `SKILL_NOT_FOUND`；
  - `taskType` 不属于 Skill → 400 `SKILL_TASK_MISMATCH`；
  - 不带 `skillId` 的旧调用完全兼容。
- 任务视图返回 `skillId`，落库 `fa_canonical_task.skill_id`（Alembic `d7e9f3a2b6c1`）。
- 主 Agent 侧验证：`oa.request.draft` / `oa.request.submit` 任务均带
  `skillId=oa.leave`；主平台真实 E2E **101/101**。

## 5. 边界

- 主 Agent 的 `e2e_full.py` 101 项不包含 OA 业务链路；OA 使用自身 pytest + 独立集成验证。
- OA 业务规则、审批链、附件/预览逻辑本次未改动。
- Skill 只做任务选择封装，不替代 OA 本地角色与 dataScope 校验。
