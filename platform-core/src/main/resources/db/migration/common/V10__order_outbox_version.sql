-- V10：可变实体补乐观锁版本列（db-conventions：所有可变实体必须 @Version）。
-- Order.priceTo() 与 OutboxEvent.markPublished() 均为可变行为；此前以
-- "append-only / ShedLock 单实例" 为由豁免，现按规范补齐（评审 M4）。
-- 骨架无存量数据，单步表达终态；H2 与 MySQL 语法一致。
ALTER TABLE orders ADD COLUMN version BIGINT DEFAULT 0 NOT NULL;
ALTER TABLE outbox_event ADD COLUMN version BIGINT DEFAULT 0 NOT NULL;
