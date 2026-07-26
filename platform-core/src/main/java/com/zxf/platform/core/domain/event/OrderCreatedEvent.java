package com.zxf.platform.core.domain.event;

import com.zxf.platform.core.domain.model.OrderId;

/**
 * 订单已创建事件（含流程实例标识）。
 *
 * <p>审计等副作用经本事件解耦：监听器 {@code @TransactionalEventListener(AFTER_COMMIT)}
 * 在事务提交后才消费——回滚的事务不留审计（文档 8.1 规则 11）。
 */
public record OrderCreatedEvent(OrderId orderId, String processInstanceId) {
}
