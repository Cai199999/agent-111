# wuye-agent

物业主 Agent（单对话框编排 Agent）。用一个对话框统一接入物业运维、合同审查等子 Agent，并提供可扩展的 Agent 接入层。

## 文档索引

| 文档 | 内容 |
|---|---|
| [`docs/01-需求与功能模块清单.md`](docs/01-需求与功能模块清单.md) | 定位、角色、用户故事、功能模块、MVP 验收 |
| [`docs/02-技术选型与架构设计.md`](docs/02-技术选型与架构设计.md) | 技术栈、总体架构、Agent 分层、路由、安全、演进 |
| [`docs/03-数据库设计文档.md`](docs/03-数据库设计文档.md) | 16 张表、索引、DDL、Flyway、数据生命周期 |
| [`docs/04-API接口设计清单.md`](docs/04-API接口设计清单.md) | 用户端/内部 API、SSE 事件、子 Agent 映射、错误码 |
| [`docs/05-最终设计方案与开发规范.md`](docs/05-最终设计方案与开发规范.md) | 代码结构、编码/测试/Git 规范、启动、阶段计划、ADR |
| [`docs/06-Agent接入与扩展规范.md`](docs/06-Agent接入与扩展规范.md) | Canonical v1、Adapter SPI、新 Agent 接入 SOP、自定义 Agent 边界 |
| [`docs/memory/00_project_brief.md`](docs/memory/00_project_brief.md) | 项目 brief 与当前状态 |

## 输入依据

- [`PropertyMaintenanceAgent-主Agent接入使用说明.md`](PropertyMaintenanceAgent-主Agent接入使用说明.md)
- [`使用手册.md`](使用手册.md)

## 当前状态

- 设计文档已同步到子 Agent 最新契约：物业 `0.8.0`、合同 `0.7.0`。
- 子 Agent P0 整改已落地：委托令牌签发、Manifest 元数据、健康/就绪、`clientTaskId` 回查、取消、事件 `seq/afterSeq`。
- 两个子 Agent 当前均未启动；主 Agent 端口/库/前缀/bucket 待登记确认。
- 主 Agent 候选端口：`8600`（Java BFF）、`8700`（Python AI）、`5182`（Web dev）。
- 物业子 Agent 已改为 `AGENT_SERVICE_PORT` 必填，登记 `8402`；需手动补 `RESOURCES.md`。
- 合同 `contract.resolve` 已上线；`contract.import` 仍为 `enabled=false`。
- 主 Agent 原型正在搭建：先做 Java BFF + 内存存储 + Mock Adapter + 单对话框。

## 资源与边界

- 独立 MySQL 库 `wuye_agent_db`、Redis 前缀 `wuye_agent:`、MinIO bucket `wuye-agent`。
- 不直连任何子 Agent 的 MySQL/Redis/MinIO/Milvus。
- 子 Agent 内部令牌只在 Java 侧；Python 不持有、不调用子 Agent。
- 共享中间件（MySQL/Redis/MinIO）只连接使用，不重启、不清理。

## 下一步

1. 评审文档。
2. 登记资源与端口。
3. Mock Agent + 内部契约代码。
4. 只读 MVP。
