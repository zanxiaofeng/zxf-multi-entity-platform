-- V4：订单金额收敛为单列（文档 5.9：Money 值对象经 AttributeConverter 落 "<amount> <currency>" 单列）。
-- 生产环境应按文档 6.1 走 expand-and-contract（expand 双写 → 全部署越过兼容点 → contract）；
-- 本骨架无存量数据，expand 与 contract 合并在一个版本内表达终态。
-- 评审修复 M2：DROP 旧列前先回填存量行——任何已有订单数据的环境执行本脚本时价格不丢失
-- （骨架无存量，回填恒为零行，纯粹是防误用的守卫；"已合入迁移不可改"纪律面向已发布
-- 环境，本骨架未发布、无 checksum 锁定环境，发布后本文件即冻结）。
ALTER TABLE orders ADD COLUMN price VARCHAR(32);
UPDATE orders SET price = CAST(price_amount AS VARCHAR(32)) || ' ' || price_currency
 WHERE price IS NULL AND price_amount IS NOT NULL;
ALTER TABLE orders DROP COLUMN price_amount;
ALTER TABLE orders DROP COLUMN price_currency;
