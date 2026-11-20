ALTER TABLE wa_chat_session
    ADD COLUMN summary TEXT NULL COMMENT '会话级记忆摘要（不跨会话检索）' AFTER title,
    ADD COLUMN summary_updated_at DATETIME(3) NULL COMMENT '摘要更新时间' AFTER summary;
