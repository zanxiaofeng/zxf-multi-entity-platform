-- V5：price 列补齐 NOT NULL（db-conventions：必需列不允许 NULL；创建主流程恒先计价后落库）。
-- 已合入迁移不可改（db-migration 规范），V4 保留原样，以补偿式正向迁移收紧约束。
-- H2 语法；MySQL 对应写法：ALTER TABLE orders MODIFY price VARCHAR(32) NOT NULL
ALTER TABLE orders ALTER COLUMN price SET NOT NULL;
