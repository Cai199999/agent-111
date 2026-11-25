-- 底座通用站内通知中心：外部 Agent 通过内部接口写入，登录用户按 actorId 拉取/已读。
CREATE TABLE wa_notification
(
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    notification_no     VARCHAR(40)  NOT NULL,
    recipient_actor_id  VARCHAR(64)  NOT NULL,
    tenant_id           VARCHAR(64)  NOT NULL DEFAULT '0',
    source_agent        VARCHAR(64)  NOT NULL,
    category            VARCHAR(32)  NOT NULL,
    title               VARCHAR(256) NOT NULL,
    content             VARCHAR(1024) NOT NULL,
    link_url            VARCHAR(512) NULL,
    biz_type            VARCHAR(64)  NULL,
    biz_id              VARCHAR(64)  NULL,
    dedup_key           VARCHAR(160) NOT NULL,
    status              VARCHAR(16)  NOT NULL DEFAULT 'UNREAD',
    created_at          DATETIME(3)  NOT NULL,
    read_at             DATETIME(3)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wa_notification_no (notification_no),
    UNIQUE KEY uk_wa_notification_dedup (dedup_key),
    KEY idx_wa_notification_recipient (recipient_actor_id, status, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;
