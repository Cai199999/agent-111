# Manifest 快照

- `contract-manifest.json`：合同 Agent 当前 Manifest（公开端点抓取）。
- 物业 Agent `0.8.0` 的 Manifest 需要 `X-Internal-Token`，当前 `.env` 中 `AGENT_PROPERTY_INTERNAL_TOKEN` 为空，尚未抓取成功。
- 物业个人资料表单当前使用主 Agent 的 fallback（字段与用户提供的 `forms.personalInfo` 一致）；填入物业内部令牌后，`GET /api/v1/profile/form` 会返回 `source=manifest` 的真实表单。
- 建议子 Agent 提交 `0.8.0` 后打 tag，并把 Manifest 快照更新到本目录，避免主 Agent 依赖漂移版本。
