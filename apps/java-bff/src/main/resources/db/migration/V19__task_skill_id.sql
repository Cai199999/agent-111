-- 物业 0.11.0：任务级 Skill 需要跨重启保留，便于审计与排查。
ALTER TABLE wa_task_binding
    ADD COLUMN skill_id VARCHAR(96) NULL COMMENT '任务级 Skill' AFTER capability_id;
