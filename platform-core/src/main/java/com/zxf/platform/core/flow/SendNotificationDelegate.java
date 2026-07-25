package com.zxf.platform.core.flow;

import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 通用任务：审批链结束后发送通知——两个实体的流程定义共用（文档 7.2 任务实现分流：
 * 通用任务进 core，实体专属任务进实体模块）。
 *
 * <p>delegate 纪律（文档规则 10）：单例无状态——禁止在字段中保存任何执行态
 * （execution、请求/响应对象），多 Job 线程并发调用同一 Spring 单例。
 */
@Component("sendNotificationDelegate")
public class SendNotificationDelegate implements JavaDelegate {

    private static final Logger log = LoggerFactory.getLogger(SendNotificationDelegate.class);

    @Override
    public void execute(DelegateExecution execution) {
        // 同步 delegate 运行在发起方线程内，MDC 天然带 entity 标（文档 7.3③）
        log.info("发送审批完成通知 orderId={} processInstanceId={}",
                execution.getVariable("orderId"), execution.getProcessInstanceId());
    }
}
