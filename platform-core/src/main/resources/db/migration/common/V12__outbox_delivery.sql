-- V12：outbox 投递治理（评审修复 P3：重投上限与死信出口）。
-- relay 无限重投 + ORDER BY createdAt 无决胜键——替换真实 MQ 后是 at-least-once，
-- 持续失败的事件将永远占据每轮扫描；attempts 记录失败次数，status 区分
-- PENDING（待投）/ DEAD（达上限放弃，ERROR 告警人工介入）。
ALTER TABLE outbox_event ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE outbox_event ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'PENDING';
