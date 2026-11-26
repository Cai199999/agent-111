-- 物业 0.11.0：AI 工单分析是低风险写（requiresConfirmation=false）。
-- 只新增权限数据，不改路由/确认/审计核心。
INSERT IGNORE INTO wa_permission (permission_code, description) VALUES
    ('capability:property.ticket.ai.analyze', 'AI 分析工单');

INSERT IGNORE INTO wa_role_permission (role_code, permission_code) VALUES
    ('BASE', 'capability:property.ticket.ai.analyze'),
    ('USER', 'capability:property.ticket.ai.analyze'),
    ('WORKER', 'capability:property.ticket.ai.analyze'),
    ('ADMIN', 'capability:property.ticket.ai.analyze');
