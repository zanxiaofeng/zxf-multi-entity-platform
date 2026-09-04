package com.zxf.platform.core.domain.model;

/**
 * Outbox 事件投递状态（评审修复 P3：重投上限与死信出口）。
 */
public enum OutboxDeliveryStatus {

    /** 待投递（初始态，relay 每轮扫描的目标）。 */
    PENDING,

    /** 死信：投递失败达 {@code OutboxEvent#MAX_ATTEMPTS} 上限后放弃，relay 不再扫描——
     *  ERROR 告警人工介入（排查下游/修数后可置回 PENDING 重投）。 */
    DEAD
}
