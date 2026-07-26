-- V4：订单金额收敛为单列（文档 5.9：Money 值对象经 AttributeConverter 落 "<amount> <currency>" 单列）。
-- 已合入迁移不可改（db-migration 规范），以补偿式正向迁移演进。
-- 生产环境应按文档 6.1 走 expand-and-contract（expand 双写 → 全部署越过兼容点 → contract）；
-- 本骨架无存量数据，expand 与 contract 合并在一个版本内表达终态。
ALTER TABLE orders ADD COLUMN price VARCHAR(32);
ALTER TABLE orders DROP COLUMN price_amount;
ALTER TABLE orders DROP COLUMN price_currency;
