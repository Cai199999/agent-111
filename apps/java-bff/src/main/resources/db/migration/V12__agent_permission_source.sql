-- H3：区分内置权限与 Agent Card 安装时写入的权限。
-- 卸载动态 Agent 时只撤销 source=AGENT:<agentKey> 的权限，不影响内置权限。
ALTER TABLE wa_permission
    ADD COLUMN source VARCHAR(64) NOT NULL DEFAULT 'BUILTIN';

CREATE INDEX idx_permission_source ON wa_permission(source);
