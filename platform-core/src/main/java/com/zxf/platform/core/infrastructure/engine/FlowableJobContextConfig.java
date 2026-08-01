package com.zxf.platform.core.infrastructure.engine;

import org.flowable.common.engine.impl.persistence.StrongUuidGenerator;
import org.flowable.common.spring.async.SpringAsyncTaskExecutor;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.flowable.spring.boot.EngineConfigurationConfigurer;
import org.flowable.spring.job.service.SpringAsyncExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Flowable Job 执行器的上下文传播（文档 7.3③）+ 引擎级调优（文档 7.7.1 组件 4/7）。
 *
 * <p>同步 delegate 运行在 Web 请求线程内，MDC 天然带 entity 标；但 async 节点与 Job
 * 执行器运行在引擎自有线程池，需要显式传播。本配置把 {@link SpringAsyncExecutor} 的
 * 任务执行器替换为 {@link EntityContextPropagatingTaskExecutor}。
 *
 * <p>引擎级调优（7.7.1 组件 4）：asyncExecutor 重试次数与失败等待时间外置到
 * {@link FlowableJobProperties}；节点级仍可用 {@code failedJobRetryTimeCycle} 覆盖。
 *
 * <p>IdGenerator（7.7.1 组件 7）：替换为 {@link StrongUuidGenerator}（比默认
 * {@code DefaultIdGenerator} 更强壮的 UUID 生成器，基于 {@code java.util.UUID}）。
 * 一个引擎只有一个 IdGenerator 且对所有实体生效（严格分库下两实体各自一个引擎实例，
 * 选型天然独立）。tenantId 在严格分库下不引入（7.7.1 组件 7 原文）。
 *
 * <p>运维配套：按实体维度监控活跃流程实例数与<b>死信 Job 数（deadletter job）</b>——
 * 后者是流程引擎最该告警的指标，非零即需人工介入。
 */
@Configuration
public class FlowableJobContextConfig {

    /** 流程 Job 执行器线程池（引擎自有线程，不经 Web Filter / @Async 传播链）。池参数外置 {@link FlowableJobProperties}。 */
    @Bean
    public ThreadPoolTaskExecutor flowableJobExecutor(FlowableJobProperties properties) {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.corePoolSize());
        executor.setMaxPoolSize(properties.maxPoolSize());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setThreadNamePrefix("flowable-job-");
        return executor;
    }

    @Bean
    public EngineConfigurationConfigurer<SpringProcessEngineConfiguration> asyncExecutorContextConfigurer(
            ThreadPoolTaskExecutor flowableJobExecutor, TaskDecorator entityContextPropagator,
            FlowableJobProperties properties) {
        return configuration -> {
            // 引擎级调优（7.7.1 组件 4）：重试次数与失败等待时间从 Properties 取
            configuration.setAsyncExecutorNumberOfRetries(properties.numberOfRetries());
            configuration.setAsyncFailedJobWaitTime(properties.failedJobWaitTime());

            if (configuration.getAsyncExecutor() instanceof SpringAsyncExecutor asyncExecutor) {
                // Flowable 8 的 setTaskExecutor 接收引擎侧 AsyncTaskExecutor：
                // 经官方适配器 SpringAsyncTaskExecutor 桥接我们的传播执行器
                asyncExecutor.setTaskExecutor(new SpringAsyncTaskExecutor(
                        new EntityContextPropagatingTaskExecutor(flowableJobExecutor, entityContextPropagator)));
            }
        };
    }

    /**
     * IdGenerator（7.7.1 组件 7）：用 {@link StrongUuidGenerator} 替换默认 IdGenerator。
     *
     * <p>一个引擎只有一个 IdGenerator 且对所有实体生效——严格分库下两实体各自独立引擎实例，
     * 选型天然独立，无需"两实体共识"协调（共识只在单引擎混部多实体时才需要）。
     */
    @Bean
    public EngineConfigurationConfigurer<SpringProcessEngineConfiguration> idGeneratorConfigurer() {
        return configuration -> configuration.setIdGenerator(new StrongUuidGenerator());
    }

    /**
     * 全局事件监听器注册（7.7.1 组件 1 + 组件 2 + 组件 6）。
     *
     * <p>{@link FlowableEngineEventListener} 是 Spring 单例，注册到引擎后所有流程定义的事件
     * 都经它——指标对接（Counter 按事件类型/processDefinitionKey/entity）+ Spring Event 桥接。
     * {@code setEventListeners} 全量订阅；按类型订阅可用 {@code setTypedEventListeners}（性能更好，
     * 但 demo 全量订阅即可）。{@code isFailOnException()=false} 保证审计/监控失败不回滚业务事务。
     *
     * <p>组件 6（候选人分配）：{@link TaskAssignmentListener} 订阅 {@code TASK_CREATED} 事件，
     * 按 {@link com.zxf.platform.core.domain.port.TaskAssignmentRule} 策略自动分配候选人——
     * 注册顺序在指标/桥接监听器之后，业务事务提交前完成候选人写入。
     */
    @Bean
    public EngineConfigurationConfigurer<SpringProcessEngineConfiguration> eventListenerConfigurer(
            FlowableEngineEventListener listener, TaskAssignmentListener assignmentListener) {
        return configuration -> configuration.setEventListeners(java.util.List.of(listener, assignmentListener));
    }

    /**
     * 自定义 EL 函数注册（7.7.1 组件 8）：BPMN 表达式可用 {@code ${bpm:currentEntity()}}。
     *
     * <p>JSON 流程变量（组件 8 另一半）由 Flowable 8 默认内置 {@code JsonType}，
     * 无需显式注册——引擎启动即支持 {@code JsonNode} 变量类型。
     */
    @Bean
    public EngineConfigurationConfigurer<SpringProcessEngineConfiguration> elFunctionConfigurer(
            CurrentEntityElFunction elFunction) {
        return configuration -> configuration.setCustomFlowableFunctionDelegates(java.util.List.of(elFunction));
    }
}
