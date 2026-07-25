package com.zxf.platform.core.order;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 订单：共享内核中真正通用的领域概念之一（文档 2.3）。
 * 结构通用，计价差异经 {@code PricingPolicy} 扩展点回填（{@link #priceTo}）。
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

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "price_amount")),
            @AttributeOverride(name = "currency", column = @Column(name = "price_currency"))
    })
    private Money price;

    protected Order() {
    }

    private Order(String item, int quantity) {
        this.item = item;
        this.quantity = quantity;
    }

    /** 通用：结构。 */
    public static Order from(CreateOrderCommand cmd) {
        return new Order(cmd.item(), cmd.quantity());
    }

    /** 差异：计价结果由扩展点算好后回填。 */
    public void priceTo(Money price) {
        this.price = price;
    }

    public Long getId() {
        return id;
    }

    public String getItem() {
        return item;
    }

    public int getQuantity() {
        return quantity;
    }

    public Money getPrice() {
        return price;
    }
}
