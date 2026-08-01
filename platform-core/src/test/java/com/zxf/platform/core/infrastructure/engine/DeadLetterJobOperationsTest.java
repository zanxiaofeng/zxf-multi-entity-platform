package com.zxf.platform.core.infrastructure.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.flowable.engine.ManagementService;
import org.flowable.job.api.DeadLetterJobQuery;
import org.flowable.job.api.Job;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 文档 7.7.1 组件 4：死信 Job 运维 API 单元测试。
 *
 * <p>Mockito mock {@link ManagementService} + {@link DeadLetterJobQuery} + {@link Job}，
 * 验证 list/count/retry 委托到引擎 API，Gauge 注册到 {@link SimpleMeterRegistry}。
 *
 * <p>Flowable 8 没有 {@code DeadLetterJob} 实体类——{@link DeadLetterJobQuery#list()} 返回
 * {@code List<Job>}（{@link Job} 继承 {@code JobInfo}，含 id / processInstanceId /
 * exceptionMessage / retries 四个所需方法）。
 */
@ExtendWith(MockitoExtension.class)
class DeadLetterJobOperationsTest {

    @Mock
    private ManagementService managementService;

    @Test
    void 列表返回死信Job摘要() {
        var job = mock(Job.class);
        when(job.getId()).thenReturn("job-1");
        when(job.getProcessInstanceId()).thenReturn("pi-1");
        when(job.getExceptionMessage()).thenReturn("下游不可达");
        when(job.getRetries()).thenReturn(0);
        var query = mock(DeadLetterJobQuery.class);
        when(query.list()).thenReturn(List.of(job));
        when(managementService.createDeadLetterJobQuery()).thenReturn(query);

        var ops = new DeadLetterJobOperations(managementService, new SimpleMeterRegistry());
        var result = ops.list();

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().jobId()).isEqualTo("job-1");
        assertThat(result.getFirst().processInstanceId()).isEqualTo("pi-1");
        assertThat(result.getFirst().exceptionMessage()).isEqualTo("下游不可达");
        assertThat(result.getFirst().retries()).isEqualTo(0);
    }

    @Test
    void 复活死信Job调用引擎API() {
        var ops = new DeadLetterJobOperations(managementService, new SimpleMeterRegistry());
        ops.retry("job-1");
        verify(managementService).moveDeadLetterJobToExecutableJob("job-1", 1);
    }

    @Test
    void 计数委托引擎查询() {
        var query = mock(DeadLetterJobQuery.class);
        when(query.count()).thenReturn(3L);
        when(managementService.createDeadLetterJobQuery()).thenReturn(query);

        var ops = new DeadLetterJobOperations(managementService, new SimpleMeterRegistry());
        assertThat(ops.count()).isEqualTo(3L);
    }

    @Test
    void 构造时注册死信Job计数Gauge() {
        var query = mock(DeadLetterJobQuery.class);
        when(query.count()).thenReturn(0L);
        when(managementService.createDeadLetterJobQuery()).thenReturn(query);

        var meterRegistry = new SimpleMeterRegistry();
        new DeadLetterJobOperations(managementService, meterRegistry);

        var gauge = meterRegistry.find("flowable.deadletter.jobs.count").gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(0.0);
    }
}
