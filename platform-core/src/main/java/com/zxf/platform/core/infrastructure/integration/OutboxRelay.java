package com.zxf.platform.core.infrastructure.integration;

import com.zxf.platform.core.context.EntityContext;
import com.zxf.platform.core.context.PlatformProperties;
import com.zxf.platform.core.domain.port.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outbox 轮询发布（文档 7.7.2 组件 12）：定时扫描未发布事件，模拟 MQ 发送。
 *
 * <p>生产替换为真实 MQ Sender（如 Kafka / RocketMQ）；本 demo 用 {@code log.info} 模拟发送，
 * 发送成功后标记 {@code publishedAt}。relay 由 ShedLock 保护防多实例重复发送（文档 7.7.2 组件 13）。
 *
 * <p>调度线程无请求上下文：手动从 {@link PlatformProperties#entity()} 重建 {@link EntityContext}
 * 与 MDC（与引擎 Job 线程的 delegate 基类同构，文档 7.3③）；try/finally 保证线程池复用时清理。
 *
 * <p>{@code @Transactional} 让脏检查生效——{@code findUnpublished} 加载的实体在同一事务内
 * 调用 {@code markPublished()}，由 Hibernate 在提交时 flush 写回 {@code published_at}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelay {

    private final OutboxRepository repository;
    private final PlatformProperties properties;

    /**
     * 扫描并发布未发出的 outbox 事件。
     *
     * <p>{@code fixedDelay=5s}：上一次执行结束后等 5 秒再开始下一次（避免任务堆积）；
     * {@code lockAtMostFor=PT4M}：兜底 4 分钟（实例宕机时锁自动过期，防死锁）；
     * {@code lockAtLeastFor=PT5S}：最少持锁 5 秒（防多实例时钟漂移导致的重复发送窗口）。
     */
    @Scheduled(fixedDelay = 5000)
    @SchedulerLock(name = "outbox-relay", lockAtMostFor = "PT4M", lockAtLeastFor = "PT5S")
    @Transactional
    public void relay() {
        var entity = properties.entity();
        EntityContext.set(entity);
        MDC.put(EntityContext.MDC_KEY, entity.name());
        try {
            var events = repository.findUnpublished(10);
            if (events.isEmpty()) {
                return;
            }
            events.forEach(event -> {
                log.info("outbox 发布 eventType={} aggregateId={}", event.eventType(), event.aggregateId());
                event.markPublished();
            });
        } catch (DataAccessException ex) {
            // @Scheduled 异常不外抛（exception-handling §7.3）：外抛只打印到容器日志，无结构化上下文。
            // catch 在 finally 之前——此刻 MDC/entity 仍在，ERROR 日志带实体维度。
            // 未发布事件下轮重扫（published_at 仍为 NULL，查询天然幂等）
            log.error("outbox relay 执行失败，本轮跳过，未发布事件下轮重扫", ex);
        } finally {
            MDC.remove(EntityContext.MDC_KEY);
            EntityContext.clear();
        }
    }
}
