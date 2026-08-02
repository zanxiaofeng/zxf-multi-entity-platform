-- Outbox relay 轮询索引（组件 12 配套）：relay 每 5s 执行
--   WHERE published_at IS NULL ORDER BY created_at（OutboxEventJpaRepository.findUnpublished）
-- 无索引时全表扫描，事件量增长后成为热点。复合索引 (published_at, created_at)
-- 同时覆盖过滤与排序（db-conventions 索引策略：常查询 WHERE 列 + ORDER BY 列）。
CREATE INDEX idx_outbox_event_published_at ON outbox_event (published_at, created_at);
