package com.zxf.platform.core.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.util.Assert;

/**
 * 订单：共享内核中真正通用的领域概念之一（文档 2.3）。
 * 结构通用，计价差异经 {@code PricingPolicy} 扩展点回填（{@link #priceTo}）。
 *
 * <p>标识与金额以值对象承载（文档 5.9 规则 3）：对外暴露 {@link OrderId} / {@link Money}，
 * 行为访问器（{@code id()}/{@code item()}/...）取代 getter（规则 9）。
 *
 * <p>持久化注记：{@link Money} 经 {@code MoneyConverter}（autoApply）落单列 {@code price}；
 * 标识保留原始 {@code Long} 字段——IDENTITY 主键由数据库生成，converter 无法承担生成职责，
 * 仅在读取边界包装为 {@link OrderId}（规则 9 的 JPA 务实例外：字段本身即表列映射）。
 */
@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String item;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "price", length = 32, nullable = false)
    private Money price;

    /** 乐观锁（db-conventions：所有可变实体必须 @Version；列由 V10 迁移提供，persist 时 Hibernate 自动置 0）。 */
    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Order() {
    }

    private Order(String item, int quantity) {
        this.item = item;
        this.quantity = quantity;
    }

    /** 通用：结构。入参为标量而非应用层 command——领域模型不依赖 application 层（文档 5.1.1）。 */
    public static Order from(String item, int quantity) {
        Assert.hasText(item, "item 不能为空");
        Assert.isTrue(quantity > 0, () -> "quantity 必须为正数，实际值: " + quantity);
        return new Order(item, quantity);
    }

    /** 差异：计价结果由扩展点算好后回填。 */
    public void priceTo(Money price) {
        Assert.notNull(price, "计价结果不能为空");
        this.price = price;
    }

    /** 订单标识（持久化后方存在）。 */
    public OrderId id() {
        Assert.state(id != null, "订单尚未持久化，无标识");
        return OrderId.of(id);
    }

    public String item() {
        return item;
    }

    public int quantity() {
        return quantity;
    }

    /** 计价结果（未计价时为 {@code null}，由流程保证先计价后使用；列级 NOT NULL，见 V5 迁移）。 */
    public @Nullable Money price() {
        return price;
    }

    /** 创建时间（UTC，落库前由 {@link #onCreate} 写入；持久化前为 {@code null}）。 */
    public @Nullable OffsetDateTime createdAt() {
        return createdAt;
    }

    /** JPA 生命周期回调：落库前写创建时间（UTC 存储，db-conventions 时间规范）。 */
    @PrePersist
    void onCreate() {
        createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}
