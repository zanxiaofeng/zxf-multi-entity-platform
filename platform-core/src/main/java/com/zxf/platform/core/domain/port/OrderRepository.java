package com.zxf.platform.core.domain.port;

import com.zxf.platform.core.domain.model.Order;
import com.zxf.platform.core.domain.model.OrderId;
import java.util.Optional;

/**
 * 订单持久化端口（文档 5.1.1）：纯契约，零框架注解。
 * JPA 适配器在 {@code infrastructure.persistence}。
 */
public interface OrderRepository {

    Order save(Order order);

    Optional<Order> findById(OrderId id);
}
