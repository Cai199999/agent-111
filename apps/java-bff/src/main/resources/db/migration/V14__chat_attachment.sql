-- 主 Agent 聊天附件：文件本体落本地/对象存储，元数据与解析结果落库。
CREATE TABLE wa_chat_attachment
(
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    attachment_no       VARCHAR(40)  NOT NULL,
    session_no          VARCHAR(40)  NULL,
    uploader_actor_id   VARCHAR(64)  NOT NULL,
    file_name           VARCHAR(255) NOT NULL,
    content_type        VARCHAR(128) NOT NULL,
    size_bytes          BIGINT       NOT NULL,
    sha256              VARCHAR(64)  NOT NULL,
    storage_path        VARCHAR(512) NOT NULL,
    parse_kind          VARCHAR(32)  NOT NULL,
    parse_status        VARCHAR(32)  NOT NULL,
    page_count          INT          NULL,
    text_chars          INT          NOT NULL DEFAULT 0,
    extracted_text      MEDIUMTEXT   NULL,
    created_at          DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wa_chat_attachment_no (attachment_no),
    KEY idx_wa_chat_attachment_uploader (uploader_actor_id, id),
    KEY idx_wa_chat_attachment_session (session_no, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;
