package com.zxf.platform.core.domain.port;

/**
 * 审计出站端口（文档 5.1.1）：审计是出站副作用，消费方（引擎 delegate 等）
 * 不得直达 observation 实现——实现为 {@code infrastructure.observation.AuditService}。
 */
public interface AuditPort {

    void record(String action, String detail);
}
