package com.zxf.platform.core.infrastructure.engine;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.flowable.engine.ManagementService;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/**
 * 死信 Job 运维 API（文档 7.7.1 组件 4 补全）：扫描 / 计数 / 复活。
 *
 * <p>死信 Job 不含 businessKey，经 processInstanceId 关联业务上下文。
 * 按异常类型分流：网络/IO 类自动复活、代码 bug 类修复后复活、非关键可删除（文档建议）。
 *
 * <p>构造时将 {@link #count()} 注册为 Micrometer Gauge
 * {@code flowable.deadletter.jobs.count}——非零即需人工介入，是流程引擎最该告警的指标。
 * Gauge 持有本实例引用，每次采集调用 {@code count()} 实时查询引擎。
 *
 * <p>Flowable 8 没有 {@code DeadLetterJob} 实体类——{@code createDeadLetterJobQuery().list()}
 * 返回 {@code List<Job>}（Job 继承 JobInfo，含 id / processInstanceId / exceptionMessage / retries）。
 */
@Component
public class DeadLetterJobOperations {

    private final ManagementService managementService;

    /**
     * @param managementService Flowable 引擎管理服务（扫描 / 计数 / 复活死信 Job）
     * @param meterRegistry      Micrometer 注册表（注册 {@code flowable.deadletter.jobs.count} Gauge）
     */
    public DeadLetterJobOperations(ManagementService managementService, MeterRegistry meterRegistry) {
        this.managementService = managementService;
        Gauge.builder("flowable.deadletter.jobs.count", this, DeadLetterJobOperations::count)
                .description("死信 Job 数量（非零即需人工介入）")
                .register(meterRegistry);
    }

    /** 扫描全部死信 Job，返回摘要列表。 */
    public List<DeadLetterJobSummary> list() {
        return managementService.createDeadLetterJobQuery().list().stream()
                .map(job -> new DeadLetterJobSummary(
                        job.getId(), job.getProcessInstanceId(),
                        job.getExceptionMessage(), job.getRetries()))
                .toList();
    }

    /** 死信 Job 计数（供 Micrometer Gauge 采集）。 */
    public long count() {
        return managementService.createDeadLetterJobQuery().count();
    }

    /** 复活死信 Job：转为可执行 Job 并设置重试次数为 1。 */
    public void retry(String jobId) {
        Assert.hasText(jobId, "jobId must not be blank");
        managementService.moveDeadLetterJobToExecutableJob(jobId, 1);
    }
}
