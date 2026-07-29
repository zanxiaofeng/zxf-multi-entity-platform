package com.zxf.platform.core.infrastructure.engine;

/** 死信 Job 摘要（组件 4 运维 API）：不含 businessKey，经 processInstanceId 关联业务。 */
public record DeadLetterJobSummary(
        String jobId,
        String processInstanceId,
        String exceptionMessage,
        int retries) {
}
