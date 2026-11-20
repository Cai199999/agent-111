-- 主 Agent 会话与消息（原型持久化）
CREATE TABLE wa_chat_session (
    session_no      VARCHAR(32)  NOT NULL COMMENT '会话编号',
    tenant_id       VARCHAR(64)  NOT NULL DEFAULT '0' COMMENT '租户',
    title           VARCHAR(128) NOT NULL DEFAULT '新会话' COMMENT '标题',
    message_count   INT          NOT NULL DEFAULT 0 COMMENT '消息数',
    created_at      DATETIME(3)  NOT NULL COMMENT '创建时间',
    last_message_at DATETIME(3)  NOT NULL COMMENT '最后消息时间',
    PRIMARY KEY (session_no),
    KEY idx_last_message (last_message_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会话';

CREATE TABLE wa_chat_message (
    message_no        VARCHAR(32)  NOT NULL COMMENT '消息编号',
    session_no        VARCHAR(32)  NOT NULL COMMENT '会话编号',
    tenant_id         VARCHAR(64)  NOT NULL DEFAULT '0' COMMENT '租户',
    role              VARCHAR(16)  NOT NULL COMMENT 'USER/ASSISTANT/SYSTEM',
    content           MEDIUMTEXT   NULL COMMENT '文本内容',
    content_type      VARCHAR(16)  NOT NULL DEFAULT 'TEXT' COMMENT 'TEXT/CARD/CONFIRM/TASK',
    metadata_json     JSON         NULL COMMENT '结构化元数据',
    client_message_id VARCHAR(64)  NULL COMMENT '前端幂等键',
    created_at        DATETIME(3)  NOT NULL COMMENT '创建时间',
    PRIMARY KEY (message_no),
    UNIQUE KEY uk_session_client (session_no, client_message_id),
    KEY idx_session_created (session_no, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='消息';
