# 子 Agent 升级通知：物业运维 0.11.0

> 来源：Property Maintenance Agent（工单 Agent）本期修改结果
> 固化日期：2026-11-26
> 协议：`agent-task-v1`；Manifest / Agent Card 版本 `0.11.0`
> 结论：主 Agent 必须重新拉取 Manifest，不能继续使用旧缓存。

---

## 1. 工单 Agent 提交记录

| Commit | 内容 |
|---|---|
| `e7cb914` | 分类路由与 AI 建议自动派单 |
| `79fb8bf` | dev 演示用户资料对齐主数据 |
| `5873f5d` | Agent 任务级 Skills 与平台全量能力 |

工作区干净，本地分支领先远端 3 个提交；未 push。

---

## 2. 给主 Agent 的汇报正文（原文）

> Property Maintenance Agent 升级至 0.11.0
>
> 协议仍为 `agent-task-v1`，Manifest / Agent Card 版本 0.11.0。请重新拉取
> Manifest，不要继续使用旧缓存。
>
> **主要变化**
>
> 1. 能力扩展到 55 个，覆盖工单全生命周期、AI 分析/决策、路由规则、知识库、
>    通知、资料审核、会话管理。
> 2. 新增 12 个任务级 Skill，选择策略为 skill-first，未覆盖或 `partial=true`
>    时回退单能力。
> 3. 任务请求/响应新增 `skillId`；传了 `skillId` 后 `taskType` 必须属于该
>    Skill。
> 4. 新增 `GET /agent/v1/capabilities/resolve`，按 Actor 返回可见能力与
>    Skill；它只用于可见性过滤，不是授权凭证。
> 5. 新增 `POST /agent/v1/knowledge/docs` 做知识文档 multipart 上传。
> 6. 权限改为子 Agent 按 Actor 当前数据库角色权威校验，提交与执行双重检查。
> 7. `WRITE` 不再等于必须确认，以 `requiresConfirmation` 为准。低风险写
>    （AI 分析、标记已读、创建/关闭会话）直接执行。
> 8. 未知能力改为提交期 400/60001，不再创建失败任务。
> 9. Agent Card `skills` 现在是 Skill 列表，不再复用了能力列表。
>
> **兼容性**
>
> - 旧调用只传 `taskType` 仍可用；
> - 响应新增 `skillId` 等字段，属于增量；
> - 委托 scope 仍按能力名限制，Skill 不替代 scope。
>
> **主 Agent 需要做的**
>
> - 升级版本识别与缓存刷新；
> - 改用 `manifest.skills` 做任务选择；
> - 错误处理适配提交期 400；
> - 确认流程改为判断 `requiresConfirmation`；
> - 解析 Agent Card 时改用真正的 Skill 列表。
>
> **验证**
>
> Java 单测 96/96，Agent 运行时契约 169/169，P0–P4 冒烟 43/43，
> AI 分析与附件删除手工验证通过。
>
> 完整通知：`docs/usage/主Agent升级通知-0.11.0.md`。

---

## 3. 主 Agent 适配清单

| # | 事项 | 主 Agent 落点 | 状态 |
|---|---|---|---|
| 1 | 版本识别与缓存刷新 | Manifest 缓存版本比对、强制刷新入口 | ✅ 已适配（2026-11-26） |
| 2 | `manifest.skills` 任务选择 | Skill 模型、路由请求/响应、Adapter 请求 | ✅ 已适配（含 partial/未覆盖回退） |
| 3 | 提交期 400/60001 错误处理 | HTTP Adapter 错误映射、自修复/追问 | ✅ 已适配 |
| 4 | 确认流程看 `requiresConfirmation` | 能力目录、路由提示、低风险写直执行 | ✅ 已适配 |
| 5 | Agent Card 解析真正 Skill 列表 | Agent Card 模型与接口 | ✅ 已适配 |
| 6 | 55 能力 / 12 Skill 目录刷新 | 启动同步 + ADMIN 强制刷新 | ⚠ 已支持；真实 55/12 待 8402 活体 |
| 7 | Capability resolve（可见性过滤） | 可选：目录同步时调用，不替代本地鉴权 | 待评估 |
| 8 | 知识文档上传（multipart） | 可选：能力透传，主 Agent 不解析正文 | 待评估 |

> 适配原则：主 Agent 只做通用接入与编排，不在底座写死物业业务字段/枚举/规则；
> 子 Agent 的角色与权限仍由子 Agent 本地数据库权威校验。

---

## 4. 主 Agent 适配结果（2026-11-26）

### 4.1 实现

| 能力 | 落点 |
|---|---|
| Skill 模型 | `AgentSkill`（skillId/taskTypes/capabilityIds/partial/requiresConfirmation） |
| Manifest 目录 | `SkillCatalogService`：版本化缓存 5 分钟、`refresh` 强制刷新 |
| 能力同步 | `ManifestSyncService`：启动同步 + `POST /api/v1/admin/agents/{key}/manifest/refresh` |
| 路由 | LLM 请求新增 `skills`；响应解析 `skillId`；不匹配时确定性回退 |
| 任务透传 | `RouteDecision/TaskRecord/AdapterTaskRequest/TaskView/SSE` 增加 `skillId`；HTTP 提交带 `skillId` |
| Agent Card | `skills` 使用真正的 Skill 列表；version 取 Manifest 版本 |
| 确认语义 | `requiresConfirmation` 为准；AI 分析等低风险写直接执行 |
| 错误映射 | 提交期 HTTP 400 → `VALIDATION`，由自修复转追问/修正 |
| 权限 | V18 增加 `capability:property.ticket.ai.analyze` |

### 4.2 验证

- 底座 Mock 升级到 `0.11.0`，声明 10 个代表性 Skill + 22 个能力（真实 55/12 由 Manifest 同步）；
- Agent Card：`version=0.11.0`，skills 含 `property.ticket.lifecycle` / `property.ticket.analyze`；
- `分析 GD20261119000107 这张工单`：路由 `property.ticket.ai.analyze`，
  `skillId=property.ticket.analyze`，`SUCCEEDED` 且无 `WAITING_CONFIRM`；
- Manifest 刷新：`version=0.11.0, capabilities=22, skills=10, source=MANIFEST`；
- Java **119/119**、Python **29/29**。

### 4.3 边界

- 真实物业 8402 未启动，55 能力/12 Skill 的活体目录与 `capabilities/resolve` 未验证；
- `capabilities/resolve` 未接入主 Agent 目录（主 Agent 仍以 Manifest/本地鉴权为准）；
- 知识文档 multipart 未接主 Agent 上传入口；
- Skill 只做任务选择封装，不替代委托 scope 与子 Agent 本地角色校验；
- 主 Agent 仍绝不自动确认，实际确认由子 Agent `WAITING_CONFIRM` 驱动。
