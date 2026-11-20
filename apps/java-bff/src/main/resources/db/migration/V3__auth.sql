-- 登录、角色层级、权限点、角色权限、用户角色
CREATE TABLE wa_user (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_no       VARCHAR(32)  NOT NULL COMMENT '用户编号',
    username      VARCHAR(64)  NOT NULL COMMENT '登录名',
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt',
    display_name  VARCHAR(64)  NOT NULL COMMENT '显示名',
    actor_id      VARCHAR(64)  NOT NULL COMMENT '子 Agent Actor（原型主映射）',
    tenant_id     VARCHAR(64)  NOT NULL DEFAULT '0',
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用/0禁用',
    created_at    DATETIME(3)  NOT NULL,
    updated_at    DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_no (user_no),
    UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='主Agent用户';

CREATE TABLE wa_role (
    role_code   VARCHAR(32) NOT NULL,
    role_name   VARCHAR(64) NOT NULL,
    parent_code VARCHAR(32) NULL COMMENT '父角色，权限继承',
    level       INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色层级';

CREATE TABLE wa_permission (
    permission_code VARCHAR(128) NOT NULL,
    description     VARCHAR(255) NULL,
    PRIMARY KEY (permission_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='权限点';

CREATE TABLE wa_role_permission (
    role_code       VARCHAR(32)  NOT NULL,
    permission_code VARCHAR(128) NOT NULL,
    PRIMARY KEY (role_code, permission_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色权限';

CREATE TABLE wa_user_role (
    user_id   BIGINT UNSIGNED NOT NULL,
    role_code VARCHAR(32)     NOT NULL,
    PRIMARY KEY (user_id, role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户角色';

ALTER TABLE wa_chat_session
    ADD COLUMN user_id BIGINT UNSIGNED NULL COMMENT '所属用户' AFTER session_no,
    ADD KEY idx_user_created (user_id, created_at);

INSERT INTO wa_role (role_code, role_name, parent_code, level) VALUES
    ('BASE',  '基础用户', NULL,   0),
    ('USER',  '普通用户', 'BASE', 10),
    ('WORKER','工人',     'BASE', 20),
    ('ADMIN', '管理员',   'WORKER', 30);

INSERT INTO wa_permission (permission_code, description) VALUES
    ('agent:property:use', '使用物业 Agent'),
    ('agent:contract:use', '使用合同 Agent'),
    ('notification:read', '查看通知'),
    ('admin:agent:manage', 'Agent 管理'),
    ('admin:user:review', '用户资料/绑定审核'),
    ('capability:property.masterdata.options', '物业主数据选项'),
    ('capability:property.profile.get', '读取个人资料'),
    ('capability:property.profile.update', '更新个人资料'),
    ('capability:property.ticket.create', '创建工单'),
    ('capability:property.ticket.query', '查询工单'),
    ('capability:property.ticket.detail', '工单详情'),
    ('capability:property.ticket.accept', '接单'),
    ('capability:property.ticket.resolve', '解决工单'),
    ('capability:property.ticket.comment.add', '工单评论'),
    ('capability:property.ticket.material.add', '工单材料'),
    ('capability:property.ticket.visit.add', '工单回访'),
    ('capability:property.ticket.hold', '挂起工单'),
    ('capability:property.ticket.resume', '恢复工单'),
    ('capability:property.ticket.finish', '结束工单'),
    ('capability:property.ticket.close', '关闭工单'),
    ('capability:property.ticket.verify', '验证工单'),
    ('capability:property.ticket.assign', '派单'),
    ('capability:property.ticket.export', '导出工单'),
    ('capability:contract.review', '合同审查'),
    ('capability:contract.resolve', '解析合同'),
    ('capability:contract.rule.create', '创建合同规则'),
    ('capability:contract.rule.list', '合同规则查询'),
    ('capability:contract.knowledge.search', '合同知识检索');

INSERT INTO wa_role_permission (role_code, permission_code) VALUES
    ('BASE', 'agent:property:use'),
    ('BASE', 'notification:read'),
    ('BASE', 'capability:property.masterdata.options'),
    ('BASE', 'capability:property.profile.get'),
    ('USER', 'capability:property.profile.update'),
    ('USER', 'capability:property.ticket.create'),
    ('USER', 'capability:property.ticket.query'),
    ('USER', 'capability:property.ticket.detail'),
    ('WORKER', 'capability:property.ticket.query'),
    ('WORKER', 'capability:property.ticket.detail'),
    ('WORKER', 'capability:property.ticket.accept'),
    ('WORKER', 'capability:property.ticket.resolve'),
    ('WORKER', 'capability:property.ticket.comment.add'),
    ('WORKER', 'capability:property.ticket.material.add'),
    ('WORKER', 'capability:property.ticket.visit.add'),
    ('WORKER', 'capability:property.ticket.hold'),
    ('WORKER', 'capability:property.ticket.resume'),
    ('WORKER', 'capability:property.ticket.finish'),
    ('WORKER', 'capability:property.ticket.close'),
    ('WORKER', 'capability:property.ticket.verify'),
    ('ADMIN', 'agent:contract:use'),
    ('ADMIN', 'admin:agent:manage'),
    ('ADMIN', 'admin:user:review'),
    ('ADMIN', 'capability:property.profile.update'),
    ('ADMIN', 'capability:property.ticket.create'),
    ('ADMIN', 'capability:property.ticket.assign'),
    ('ADMIN', 'capability:property.ticket.export'),
    ('ADMIN', 'capability:contract.review'),
    ('ADMIN', 'capability:contract.resolve'),
    ('ADMIN', 'capability:contract.rule.create'),
    ('ADMIN', 'capability:contract.rule.list'),
    ('ADMIN', 'capability:contract.knowledge.search');
