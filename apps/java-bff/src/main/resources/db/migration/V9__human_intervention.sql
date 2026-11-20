CREATE TABLE wa_human_intervention (
    task_no       VARCHAR(32) NOT NULL,
    status        VARCHAR(16) NOT NULL COMMENT 'PENDING/CLAIMED/RESOLVED',
    assignee_id   BIGINT UNSIGNED DEFAULT NULL,
    assignee_name VARCHAR(64) DEFAULT NULL,
    note          VARCHAR(512) DEFAULT NULL,
    created_at    DATETIME(3) NOT NULL,
    updated_at    DATETIME(3) NOT NULL,
    PRIMARY KEY (task_no),
    KEY idx_status_updated (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='人工介入队列';

INSERT IGNORE INTO wa_permission (permission_code, description) VALUES
    ('human:manage', '人工介入队列认领/处理');

INSERT IGNORE INTO wa_role_permission (role_code, permission_code) VALUES
    ('WORKER', 'human:manage');
