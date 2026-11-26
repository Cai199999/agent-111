-- 飞轮样本：任务终态快照，供离线评测/偏好数据使用。
-- JSONL 仍作为默认/测试后端；mysql profile 落本表，便于查询与后续接评测流水线。
CREATE TABLE wa_flywheel_sample
(
    sample_id      VARCHAR(64)  NOT NULL,
    event_id       VARCHAR(64)  NOT NULL,
    event_type     VARCHAR(96)  NOT NULL,
    task_no        VARCHAR(64)  NULL,
    session_no     VARCHAR(64)  NULL,
    agent_key      VARCHAR(96)  NULL,
    capability_id  VARCHAR(128) NULL,
    actor_id       VARCHAR(64)  NULL,
    tenant_id      VARCHAR(64)  NULL,
    status         VARCHAR(24)  NOT NULL,
    input_json     MEDIUMTEXT   NULL,
    output_json    MEDIUMTEXT   NULL,
    error_json     MEDIUMTEXT   NULL,
    duration_ms    BIGINT       NOT NULL DEFAULT 0,
    occurred_at    DATETIME(3)  NOT NULL,
    created_at     DATETIME(3)  NOT NULL,
    PRIMARY KEY (sample_id),
    UNIQUE KEY uk_wa_flywheel_event (event_id),
    KEY idx_wa_flywheel_created (created_at),
    KEY idx_wa_flywheel_task (task_no),
    KEY idx_wa_flywheel_agent (agent_key, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;
