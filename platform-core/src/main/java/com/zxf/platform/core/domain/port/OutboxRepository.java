package com.zxf.platform.core.domain.port;

import com.zxf.platform.core.domain.model.OutboxEvent;
import java.util.List;

/**
 * Outbox 持久化端口（文档 7.7.2 组件 12）：纯契约，零框架注解。
 * JPA 适配器在 {@code infrastructure.persistence}。
 *
 * <p>发布标记不走端口方法：{@code OutboxRelay} 在同一事务内对 {@code findUnpublished}
 * 加载的实体直接调用 {@link OutboxEvent#markPublished()}，由 JPA 脏检查写回。
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
}
