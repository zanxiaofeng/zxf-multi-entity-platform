package com.zxf.platform.core.domain.model;

/**
 * 订单生命周期状态（评审修复 M3：风控拒绝语义显式化——此前拒绝订单与正常订单在库中
 * 无区别，下游仍收到 ORDER_CREATED"已创建"事件）。
 *
 * <p>骨架仅区分"已创建（进入审批流）"与"风控拒绝（流程终止）"两态；扩展审批中间态
 * （已批准 / 已驳回等）时按需追加，转移守卫收敛在 {@link Order#markRiskRejected()}。
 */
public enum OrderStatus {

    /** 已创建：订单落库并进入审批流程（初始态，持久化默认值见 V11 迁移）。 */
    CREATED,

    /** 风控拒绝：审批流程在风控节点终止——订单行保留供审计追溯，下游事件以 ORDER_REJECTED 广播。 */
    RISK_REJECTED
}
