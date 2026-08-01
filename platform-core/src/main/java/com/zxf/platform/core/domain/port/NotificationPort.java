package com.zxf.platform.core.domain.port;

/**
 * 通知出站端口（文档 7.7.2 组件 11）：通知发送的领域契约。
 *
 * <p>实现位于 infrastructure 层（{@code NotificationClient}），由 RestClient + Resilience4j
 * 包装下游 HTTP 调用。消费方（引擎 delegate 等）依赖此端口而非具体实现，
 * 遵循六边形架构依赖方向（与 {@link AuditPort} 同构）。
 *
 * <p>方法语义：调用成功即返回；失败（下游不可达、4xx/5xx 等）抛
 * {@code NotificationFailedException}——交由 Flowable Job 重试→死信机制处理（文档 7.7.1 组件 4）。
 */
public interface NotificationPort {

    /**
     * 发送审批完成通知到下游通知服务。
     *
     * @param orderId           业务订单号（写入请求体）
     * @param processInstanceId Flowable 流程实例 id（写入请求体，供下游关联）
     */
    void send(String orderId, String processInstanceId);
}
