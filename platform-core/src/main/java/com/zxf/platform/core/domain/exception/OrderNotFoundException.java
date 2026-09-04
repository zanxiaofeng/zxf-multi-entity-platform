package com.zxf.platform.core.domain.exception;

import com.zxf.platform.core.domain.model.OrderId;
import java.util.Objects;

/**
 * 订单不存在（exception-handling §2/§3.1：资源不存在 → 类型化领域异常，映射 404）。
 *
 * <p>替代 Controller 内拼装 {@code ResponseStatusException}——HTTP 语义（404）只属于
 * 边界的 {@code RestExceptionHandler}，Controller 禁止抛业务异常（§4.1）；领域异常本身
 * 不携带状态码（原则 6）。
 *
 * <p>{@link #CODE} 是客户端稳定契约（ProblemDetail 的 {@code code} 属性暴露，e2e 断言守护），
 * 消息文案可改而 CODE 不可改；{@code orderId} 为排查上下文（类型化字段，供日志使用）。
 *
 * <p>暂不抽 {@code DomainException} 公共基类（§3.2 标注「可选」）：当前领域异常仅此一个
 * （{@code RuleViolationException} 属 application 层输入违反、{@code NotificationFailedException}
 * 属端口契约异常，均独立成类），handler 逐异常声明无样板膨胀——待领域异常 ≥3 个再收敛基类。
 */
public class OrderNotFoundException extends RuntimeException {

    /** 稳定错误码：客户端按此判断「订单不存在」，全大写下划线（exception-handling §3.1）。 */
    public static final String CODE = "ORDER_NOT_FOUND";

    private final String orderId;

    public OrderNotFoundException(OrderId orderId) {
        // super 的参数求值先于 super 调用——requireNonNull 在 value() 解引用前生效，
        // 传 null 得到带契约消息的失败而非无指向性的 NPE
        super("订单不存在: " + Objects.requireNonNull(orderId, "orderId 不能为空").value());
        this.orderId = orderId.value();
    }

    /** 排查上下文：订单标识（字符串形态），供 handler 日志使用。 */
    public String getOrderId() {
        return orderId;
    }
}
