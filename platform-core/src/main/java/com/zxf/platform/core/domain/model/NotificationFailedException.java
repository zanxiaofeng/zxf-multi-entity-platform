package com.zxf.platform.core.domain.model;

/**
 * 通知发送失败（文档 7.7.1 组件 4 技术异常路径示范；7.7.2 组件 11 后由真实下游调用触发）。
 *
 * <p>作为 {@code NotificationPort.send()} 的契约异常——下游不可达 / 4xx / 5xx 经 Resilience4j
 * Retry 耗尽后由 {@code NotificationClient} 包装抛出；{@code SendNotificationDelegate}
 * 不捕获、原样传播至 Flowable Job 重试机制（配合 {@code failedJobRetryTimeCycle}），
 * 重试耗尽进 {@code ACT_RU_DEADLETTER_JOB}（文档 7.7.1 组件 4）。
 *
 * <p><b>所在层</b>：domain.model 而非 infrastructure.engine——组件 11 起，
 * {@code engine}（delegate 消费方）与 {@code integration}（client 实现方）均需引用此异常；
 * 放 adapter 层会破坏 onion 规则（adapter 间禁相互依赖），上提到 domain 让两端均能依赖。
 *
 * <p>作为 {@link RuntimeException} 子类与 Spring 事务回滚语义对齐；替代裸
 * {@code RuntimeException}（java-coding-standard §18 禁止裸 RuntimeException）。
 */
public class NotificationFailedException extends RuntimeException {

    public NotificationFailedException(String message) {
        super(message);
    }

    /** 包装下游异常时使用，保留 cause 满足异常链规范（java-coding-standard §11）。 */
    public NotificationFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
