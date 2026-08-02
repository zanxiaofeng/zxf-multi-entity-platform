package com.zxf.platform.core.infrastructure.scheduling;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ShedLock 定时任务选主（文档 7.7.2 组件 13）。
 *
 * <p>严格分库下 per-entity 库内 JDBC 锁存储可用；global 任务（跨实体共享）的锁存储需
 * Redis / K8s Lease（文档 5.2.6）。demo 单实例不实际竞争，配置示范为主。
 *
 * <p>{@code @EnableScheduling} 与 {@code @EnableSchedulerLock} 同处声明：项目此前只有
 * {@code @EnableAsync}（{@link com.zxf.platform.core.context.AsyncConfig}），无定时任务入口；
 * 引入 ShedLock 后由本类一并开启调度与 AOP 锁拦截。
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT5M")
public class ShedLockConfig {

    /**
     * JDBC 锁存储：基于 per-entity 数据源的 {@code shedlock} 表（由 Flyway V7 创建）。
     *
     * <p>默认 {@code lockAtMostFor = PT5M}（5 分钟）作为兜底——任务异常未释放锁时，
     * 5 分钟后自动过期，避免死锁；正常执行完毕时 ShedLock 会主动释放（缩短至实际执行时长）。
     *
     * @param dataSource per-entity 主数据源（Flyway 已在该库建好 {@code shedlock} 表）
     * @return LockProvider 装配给 {@code @SchedulerLock} AOP 拦截器
     */
    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(dataSource, "shedlock");
    }
}
