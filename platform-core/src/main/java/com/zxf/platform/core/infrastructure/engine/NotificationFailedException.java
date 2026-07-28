package com.zxf.platform.core.infrastructure.engine;

/**
 * 通知发送失败（文档 7.7.1 组件 4 技术异常路径示范）。
 *
 * <p>作为 {@link SendNotificationDelegate} 的技术异常类型，替代裸 {@link RuntimeException}
 * （java-coding-standard §18 禁止裸 RuntimeException）。作为 {@code RuntimeException} 子类，
 * 仍能触发 Flowable Job 重试机制（配合 {@code failedJobRetryTimeCycle}），耗尽进死信表——
 * 与 {@code BpmnError}（组件 5，走 BPMN 边界事件分支、不重试）形成对照。
 */
public class NotificationFailedException extends RuntimeException {

    public NotificationFailedException(String message) {
        super(message);
    }
}
