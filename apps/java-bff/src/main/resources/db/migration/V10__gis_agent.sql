-- 第三个演示 Agent：时空资产 Agent（Mock）的权限点。
-- 新增 Agent 只加权限数据，不改 Java 核心路由/确认/审计。
INSERT IGNORE INTO wa_permission (permission_code, description) VALUES
    ('agent:geo-asset-agent:use', '使用时空资产 Agent'),
    ('capability:gis.parcel.query', '查询地块'),
    ('capability:gis.change.query', '查询遥感变化图斑'),
    ('capability:gis.patrol.create', '创建巡查任务');

INSERT IGNORE INTO wa_role_permission (role_code, permission_code) VALUES
    ('BASE', 'agent:geo-asset-agent:use'),
    ('BASE', 'capability:gis.parcel.query'),
    ('BASE', 'capability:gis.change.query'),
    ('WORKER', 'capability:gis.patrol.create');
