package com.zxf.platform.core.domain.port;

import com.zxf.platform.core.domain.model.OutboxEvent;
import java.util.List;

/**
 * Outbox 持久化端口（文档 7.7.2 组件 12）：纯契约，零框架注解。
 * JPA 适配器在 {@code infrastructure.persistence}。
 *
 * <p>{@code markPublished} 依赖 JPA 脏检查——调用方必须在事务内（事务提交时 flush）。
 */
public interface OutboxRepository {

    /**
     * 保存事件（与业务表同事务调用）。
     *
     * @param event 待保存事件（不允许 {@code null}）
     */
    void save(OutboxEvent event);

    /**
     * 查询未发布事件（按创建时间升序，限制条数）。
     *
     * @param limit 最多返回条数（正数）
     * @return 未发布事件列表（空列表表示无待发布）
     */
    List<OutboxEvent> findUnpublished(int limit);

    /**
     * 标记指定事件为已发布（依赖调用方事务——脏检查写回）。
     *
     * @param id 事件标识（不允许 {@code null}）
     */
    void markPublished(Long id);
}
