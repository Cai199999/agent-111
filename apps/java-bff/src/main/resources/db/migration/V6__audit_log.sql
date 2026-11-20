CREATE TABLE wa_audit_log (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    trace_id      VARCHAR(64)  DEFAULT NULL,
    user_id       BIGINT UNSIGNED DEFAULT NULL,
    username      VARCHAR(64)  DEFAULT NULL,
    action        VARCHAR(64)  NOT NULL COMMENT 'LOGIN/TASK_SUBMIT/TASK_CONFIRM/TASK_CANCEL/SESSION_DELETE/TOOL_EXECUTE',
    resource      VARCHAR(64)  DEFAULT NULL COMMENT 'AUTH/TASK/SESSION/TOOL',
    resource_id   VARCHAR(128) DEFAULT NULL,
    agent_key     VARCHAR(64)  DEFAULT NULL,
    capability_id VARCHAR(128) DEFAULT NULL,
    status        VARCHAR(16)  NOT NULL COMMENT 'SUCCESS/FAILED',
    detail_json   JSON         DEFAULT NULL,
    created_at    DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_created (created_at),
    KEY idx_user_created (user_id, created_at),
    KEY idx_action_created (action, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='操作审计';
