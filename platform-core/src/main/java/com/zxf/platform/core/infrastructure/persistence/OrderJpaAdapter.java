package com.zxf.platform.core.infrastructure.persistence;

import com.zxf.platform.core.domain.model.Order;
import com.zxf.platform.core.domain.model.OrderId;
import com.zxf.platform.core.domain.port.OrderRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/**
 * {@link OrderRepository} 的 JPA 适配器（文档 5.1.1：出站适配器实现 domain 端口）。
 * 标识转换（{@link OrderId} ↔ 数据库主键）收敛在此，应用层只见值对象。
 *
 * <p>命名约定（architecture.md §2）：domain 端口实现的适配器 = {@code {Entity}JpaAdapter}，
 * 包私有 Spring Data 接口 = {@code {Entity}JpaRepository}。
 */
@Component
@RequiredArgsConstructor
public class OrderJpaAdapter implements OrderRepository {

    private final OrderJpaRepository delegate;

    @Override
    public Order save(Order order) {
        Assert.notNull(order, "订单不能为 null");
        return delegate.save(order);
    }

    @Override
    public Optional<Order> findById(OrderId id) {
        Assert.notNull(id, "订单标识不能为 null");
        return delegate.findById(Long.valueOf(id.value()));
    }
}
