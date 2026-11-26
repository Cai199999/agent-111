-- 事件 Outbox：先落库再投递，保证任务事件跨重启不丢。
-- 当前 relay 投递到进程内事件总线；后续替换 Kafka/Redis Stream 时保留本表作为可靠发件箱。
CREATE TABLE wa_event_outbox
(
    event_id         VARCHAR(64)  NOT NULL,
    event_type       VARCHAR(96)  NOT NULL,
    event_version    INT          NOT NULL,
    occurred_at      DATETIME(3)  NOT NULL,
    source           VARCHAR(64)  NOT NULL,
    trace_id         VARCHAR(128) NULL,
    actor_id         VARCHAR(64)  NULL,
    tenant_id        VARCHAR(64)  NULL,
    subject          VARCHAR(128) NOT NULL,
    idempotency_key  VARCHAR(192) NOT NULL,
    payload_json     MEDIUMTEXT   NOT NULL,
    status           VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    attempts         INT          NOT NULL DEFAULT 0,
    last_error       VARCHAR(512) NULL,
    created_at       DATETIME(3)  NOT NULL,
    dispatched_at    DATETIME(3)  NULL,
    PRIMARY KEY (event_id),
    KEY idx_wa_event_outbox_pending (status, created_at),
    KEY idx_wa_event_outbox_subject (subject, created_at),
    KEY idx_wa_event_outbox_idempotency (idempotency_key)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;
