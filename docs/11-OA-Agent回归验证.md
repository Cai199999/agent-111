# OA 子 Agent 回归验证（2026-11-26）

> 范围：OA `flow-agent` 1.0.0 的自身测试 + 主 Agent → OA 真实集成链路。
> 边界：只修复 OA 的确认过期时间和测试夹具，不改 OA 业务规则。

---

## 1. 运行态

| 服务 | 地址 | 状态 |
|---|---|---|
| OA `flow-agent` | `127.0.0.1:8810` | UP |
| 主 Agent | `127.0.0.1:8600` | UP |
| 主 Agent 注册表 | `oa-entry-agent` | `1.0.0`，18 能力，0 Skill |

主 Agent 健康检查：`health=UP`、`ready=UP`，动态 Agent 调用正常。

> OA 1.0.0 仍以单能力接入，未声明任务级 Skills；主 Agent 的 skill-first 自动回退到单能力。

## 2. OA 自身测试

- `pytest`：**158/158 PASS**
- 修复 1：会议测试夹具原固定 `2026-11-25`，相对当前日期已过期，
  被“过去时间”规则拦截；改为 `case.now + 1 天`。
- 修复 2：MySQL 读出的 `expires_at` 为 naive datetime，
  与 aware UTC 比较触发 `TypeError`，导致主 Agent 读取确认任务时 OA 返回 500；
  已统一按 UTC 处理，并新增 naive future/past 两个断言。

## 3. 主 Agent → OA 真实链路

| 场景 | 结果 |
|---|---|
| 会议室预约草稿 | `SUCCEEDED`，`submittable=true`，审批链正常 |
| 请假草稿 | `SUCCEEDED`，`submittable=true`，审批链正常 |
| 提交请假单 | `WAITING_CONFIRM`，确认卡正常 |
| 确认提交 | `SUCCEEDED`，生成 `LV-20261126-0001` |

请求示例：

```text
下周三请两天年假回老家
提交请假单 DRF-...
```

## 4. 边界

- OA 尚未声明 Skills；主 Agent 按单能力路由，未做 skill-first 优化。
- 主 Agent 的 `e2e_full.py` 101 项不包含 OA 业务链路；OA 使用自身 pytest + 独立集成验证。
- OA 业务规则、审批链、附件/预览逻辑本次未改动。
