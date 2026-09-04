package com.zxf.platform.core.domain.port;

/**
 * 审计出站端口（文档 5.1.1）：审计是出站副作用，消费方（引擎 delegate 等）
 * 不得直达 observation 实现——实现为 {@code infrastructure.observation.AuditService}。
 *
 * <p><b>当前无外部消费方（有意保留）</b>：副作用路径已全部事件化（文档 8.1 规则 11——
 * {@code OrderCreatedEvent} / {@code ApprovalNotifiedEvent} 经 AFTER_COMMIT 监听器消费，
 * 此前 delegate 同步调用 {@code record} 的路径已废除）。保留本端口作出站端口形态的
 * 示范：后续业务模块产生「事务内直接调用审计」的场景时（如非引擎触发的领域事件之外
 * 的同步审计需求），消费方依赖本端口而非直达实现。
 */
public interface AuditPort {

    void record(String action, String detail);
}
