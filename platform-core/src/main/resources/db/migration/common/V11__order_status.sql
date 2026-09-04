-- V11：订单生命周期状态（评审修复 M3：风控拒绝语义显式化）。
-- 此前风控拒绝的订单与正常订单在库中无区别，下游仍收到 ORDER_CREATED"已创建"事件——
-- 新增 status 列区分 CREATED / RISK_REJECTED，拒绝订单行保留供审计追溯，
-- 下游事件由应用服务按状态分流为 ORDER_REJECTED。
-- DEFAULT 'CREATED' 回填存量行（骨架无存量；任何已有环境执行本脚本也不产生语义变化）。
ALTER TABLE orders ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'CREATED';
