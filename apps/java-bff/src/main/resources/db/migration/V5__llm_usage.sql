CREATE TABLE wa_llm_usage (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    operation         VARCHAR(32)  NOT NULL COMMENT 'ROUTE/EXPLAIN/REPAIR',
    prompt_tokens     INT          NOT NULL DEFAULT 0,
    completion_tokens INT          NOT NULL DEFAULT 0,
    total_tokens      INT          NOT NULL DEFAULT 0,
    cost_cny          DECIMAL(12,6) NOT NULL DEFAULT 0,
    created_at        DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_created (created_at),
    KEY idx_operation (operation, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='LLM token/成本';
