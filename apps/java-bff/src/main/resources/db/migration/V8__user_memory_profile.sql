CREATE TABLE wa_user_memory_profile (
    user_id           BIGINT UNSIGNED NOT NULL,
    profile_json      JSON         NOT NULL,
    confidence        DECIMAL(4,3) NOT NULL DEFAULT 0,
    source_session_no VARCHAR(32)  DEFAULT NULL,
    updated_at        DATETIME(3)  NOT NULL,
    PRIMARY KEY (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='全局用户画像（稳定偏好，不含敏感个人信息）';
