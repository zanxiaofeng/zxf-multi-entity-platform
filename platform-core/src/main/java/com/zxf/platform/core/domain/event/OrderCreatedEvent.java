package com.zxf.platform.core.domain.event;

import com.zxf.platform.core.domain.model.OrderId;
import com.zxf.platform.core.domain.model.OrderStatus;

/**
 * 订单已创建事件（含流程实例标识与生命周期状态）。
 *
 * <p>审计等副作用经本事件解耦：监听器 {@code @TransactionalEventListener(AFTER_COMMIT)}
 * 在事务提交后才消费——回滚的事务不留审计（文档 8.1 规则 11）。
 *
 * <p>状态组件（评审修复 M3）：风控拒绝的订单同样发本事件（订单行确实落库），消费方
 * （审计等）按 {@code status} 区分终态；下游广播语义的分流在 Outbox 侧
 * （ORDER_CREATED / ORDER_REJECTED）。
 */
public record OrderCreatedEvent(OrderId orderId, String processInstanceId, OrderStatus status) {
}
