package com.zxf.platform.core.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.util.Assert;

/**
 * Outbox 事件（文档 7.7.2 组件 12）：与业务表同事务写入，relay 轮询发布。
 *
 * <p>使用 class 而非 record——JPA 实体需无参构造 + 可变字段（{@code @Entity} 不能作用于
 * record）。行为访问器风格（{@code id()}/{@code aggregateType()}/...）与 {@link Order} 一致，
 * 状态变更（标记已发布）走领域方法 {@link #markPublished()}（文档 8.1 规则 12，5.9 军规 9）。
 *
 * <p>事件载荷 {@code payload} 暂作 {@code null}（demo）；生产扩展为 JSON 序列化后的领域事件快照。
 * 创建时间由构造器写入（UTC 存储，db-conventions 时间规范）；发布时间由 relay 标记。
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aggregate_type", nullable = false, length = 64)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "payload", length = 2000)
    private String payload;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    /** 乐观锁（db-conventions：所有可变实体必须 @Version；列由 V10 迁移提供）。ShedLock 已保证 relay 单实例执行，此处为并发更新丢失的代码级兜底。 */
    @Version
    private Long version;

    /** JPA 要求：无参构造（protected 防止应用层直接new）。 */
    protected OutboxEvent() {
    }

    /**
     * 创建一条未发布的 Outbox 事件（创建时间由本构造器落 UTC）。
     *
     * @param aggregateType 聚合类型（如 {@code "ORDER"}），标识业务分类
     * @param aggregateId 聚合标识（字符串形态，兼容流程变量 / REST 路径）
     * @param eventType 事件类型（如 {@code "ORDER_CREATED"}）
     * @param payload 事件载荷（可 {@code null}，demo 不携带快照）
     */
    public OutboxEvent(String aggregateType, String aggregateId, String eventType, @Nullable String payload) {
        Assert.hasText(aggregateType, "aggregateType 不能为空");
        Assert.hasText(aggregateId, "aggregateId 不能为空");
        Assert.hasText(eventType, "eventType 不能为空");
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    /** 事件标识（持久化后方存在）。 */
    public Long id() {
        return id;
    }

    public String aggregateType() {
        return aggregateType;
    }

    public String aggregateId() {
        return aggregateId;
    }

    public String eventType() {
        return eventType;
    }

    public @Nullable String payload() {
        return payload;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    public @Nullable OffsetDateTime publishedAt() {
        return publishedAt;
    }

    /** 标记已发布：relay 发送成功后调用（依赖 JPA 脏检查，relay 方法 {@code @Transactional}）。 */
    public void markPublished() {
        this.publishedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}
