-- 补齐属性/知识/相似工单能力权限，保证管理员拥有 21 项物业能力
INSERT IGNORE INTO wa_permission (permission_code, description) VALUES
    ('capability:property.knowledge.search', '物业知识检索'),
    ('capability:property.ticket.similar', '相似工单'),
    ('capability:property.ticket.cancel', '取消工单');

INSERT IGNORE INTO wa_role_permission (role_code, permission_code) VALUES
    ('BASE', 'capability:property.knowledge.search'),
    ('BASE', 'capability:property.ticket.similar'),
    ('ADMIN', 'capability:property.ticket.cancel');
