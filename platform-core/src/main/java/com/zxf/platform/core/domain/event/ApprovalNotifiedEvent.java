package com.zxf.platform.core.domain.event;

import com.zxf.platform.core.domain.model.OrderId;

/**
 * 审批完成通知已发出事件（评审修复 P3：审计副作用 AFTER_COMMIT 化）。
 *
 * <p>{@code SendNotificationDelegate} 在引擎 Job 事务内发布本事件（仅注册意图），
 * {@code AuditService} 以 {@code @TransactionalEventListener(AFTER_COMMIT)} 消费——
 * 此前 delegate 在 Job 事务内<b>同步</b>调用 {@code AuditPort.record}，Job 事务在记录
 * 之后回滚时会留下幻影审计条目（与项目自身的文档 8.1 规则 11 纪律相悖）。
 */
public record ApprovalNotifiedEvent(OrderId orderId, String processInstanceId) {
}
