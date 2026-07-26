package com.zxf.platform.core.domain.port;

import com.zxf.platform.core.domain.model.Order;

/**
 * 审批出站端口（文档 5.1.1、7.1）：应用层只声明"发起审批"意图，不感知
 * Flowable——引擎集成是 {@code infrastructure.engine} 适配器的职责。
 *
 * @return 流程实例 ID
 */
public interface OrderApprovalPort {

    String startApproval(Order order);
}
