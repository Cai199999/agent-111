-- H1：运行时安装的 Agent 注册表。
-- 只存安装元数据与加密后的内部令牌；不存业务数据。
-- kind=HTTP 时由启动加载重建 DynamicHttpAgentAdapter；
-- kind=TEMPLATE 时由启动加载按 template_key 重建内置模板 Agent。
CREATE TABLE IF NOT EXISTS wa_agent_registry (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    agent_key             VARCHAR(64)  NOT NULL,
    display_name          VARCHAR(128) NOT NULL,
    contract_version      VARCHAR(32)  NOT NULL,
    dialect               VARCHAR(32)  NOT NULL,
    kind                  VARCHAR(16)  NOT NULL DEFAULT 'HTTP',
    template_key          VARCHAR(64)  NULL,
    descriptor_json       JSON         NULL,
    base_url              VARCHAR(255) NULL,
    internal_token_cipher TEXT         NULL,
    audience              VARCHAR(64)  NULL,
    actor_id              VARCHAR(64)  NULL,
    roles                 VARCHAR(64)  NULL,
    tenant_id             VARCHAR(64)  NULL,
    use_delegation        TINYINT      NOT NULL DEFAULT 0,
    health_path           VARCHAR(128) NULL,
    ready_path            VARCHAR(128) NULL,
    manifest_path         VARCHAR(128) NULL,
    status                VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    installed_by          VARCHAR(64)  NULL,
    installed_at          DATETIME(3)  NOT NULL,
    updated_at            DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_key (agent_key),
    KEY idx_status (status),
    KEY idx_kind (kind)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
