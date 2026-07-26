-- V6：订单补创建时间（db-conventions：核心业务实体应有审计时间戳，UTC 存储；
-- append-only 场景 @Version 可选，但 created_at 对排查与对账必需）。
-- 骨架无存量数据，单步表达终态；生产有存量时按 db-migration 三步法（加列 → 回填 → 收紧）。
-- H2 语法（OffsetDateTime 映射列）；MySQL 对应写法：TIMESTAMP NOT NULL
ALTER TABLE orders ADD COLUMN created_at TIMESTAMP WITH TIME ZONE NOT NULL;
