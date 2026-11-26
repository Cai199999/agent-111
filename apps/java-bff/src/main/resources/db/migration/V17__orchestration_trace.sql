-- 编排审计：记录每次 PLAN/VERIFY 的阶段、判定、能力与步骤状态。
-- 不记录 input/output 原文，避免把业务数据复制到观测面。
CREATE TABLE wa_orchestration_trace
(
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    trace_id           VARCHAR(128) NULL,
    session_no         VARCHAR(64)  NULL,
    phase              VARCHAR(16)  NOT NULL,
    verdict            VARCHAR(32)  NOT NULL,
    clarification      VARCHAR(1024) NULL,
    capabilities_json  TEXT         NULL,
    statuses_json      TEXT         NULL,
    occurred_at        DATETIME(3)  NOT NULL,
    created_at         DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_wa_orch_time (occurred_at),
    KEY idx_wa_orch_verdict (verdict),
    KEY idx_wa_orch_trace (trace_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;
