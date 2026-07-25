package com.zxf.platform.core.flow;

import org.flowable.common.spring.async.SpringAsyncTaskExecutor;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.flowable.spring.boot.EngineConfigurationConfigurer;
import org.flowable.spring.job.service.SpringAsyncExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Flowable Job 执行器的上下文传播（文档 7.3③）。
 *
 * <p>同步 delegate 运行在 Web 请求线程内，MDC 天然带 entity 标；但 async 节点与 Job
 * 执行器运行在引擎自有线程池，需要显式传播。本配置把 {@link SpringAsyncExecutor} 的
 * 任务执行器替换为 {@link EntityContextPropagatingTaskExecutor}。
 *
 * <p>运维配套：按实体维度监控活跃流程实例数与<b>死信 Job 数（deadletter job）</b>——
 * 后者是流程引擎最该告警的指标，非零即需人工介入。
 */
@Configuration
public class FlowableJobContextConfig {

    /** 流程 Job 执行器线程池（引擎自有线程，不经 Web Filter / @Async 传播链）。 */
    @Bean
    public ThreadPoolTaskExecutor flowableJobExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("flowable-job-");
        return executor;
    }

    @Bean
    public EngineConfigurationConfigurer<SpringProcessEngineConfiguration> asyncExecutorContextConfigurer(
            ThreadPoolTaskExecutor flowableJobExecutor, TaskDecorator entityContextPropagator) {
        return configuration -> {
            if (configuration.getAsyncExecutor() instanceof SpringAsyncExecutor asyncExecutor) {
                // Flowable 8 的 setTaskExecutor 接收引擎侧 AsyncTaskExecutor：
                // 经官方适配器 SpringAsyncTaskExecutor 桥接我们的传播执行器
                asyncExecutor.setTaskExecutor(new SpringAsyncTaskExecutor(
                        new EntityContextPropagatingTaskExecutor(flowableJobExecutor, entityContextPropagator)));
            }
        };
    }
}
