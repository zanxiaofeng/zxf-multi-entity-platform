package com.zxf.platform.core.infrastructure.persistence;

import com.zxf.platform.core.domain.model.OutboxDeliveryStatus;
import com.zxf.platform.core.domain.model.OutboxEvent;
import com.zxf.platform.core.domain.port.OutboxRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/**
 * {@link OutboxRepository} 的 JPA 适配器（文档 5.1.1：出站适配器实现 domain 端口）。
 *
 * <p>发布标记不经适配器：{@code OutboxRelay.relay()} 在 {@code @Transactional} 内对
 * {@code findUnpublished} 加载的实体直接调用 {@link OutboxEvent#markPublished()}，
 * 依赖同一事务的脏检查写回 {@code published_at}。
 *
 * <p>命名约定（architecture.md §2）：domain 端口实现的适配器 = {@code {Entity}JpaAdapter}，
 * 包私有 Spring Data 接口 = {@code {Entity}JpaRepository}。
 */
@Component
@RequiredArgsConstructor
public class OutboxEventJpaAdapter implements OutboxRepository {

    private final OutboxEventJpaRepository delegate;

    @Override
    public void save(OutboxEvent event) {
        Assert.notNull(event, "事件不能为 null");
        delegate.save(event);
    }

    @Override
    public List<OutboxEvent> findUnpublished(int limit) {
        Assert.isTrue(limit > 0, () -> "limit 必须为正数，实际值: " + limit);
        return delegate.findByStatusUnpublished(OutboxDeliveryStatus.PENDING, PageRequest.of(0, limit));
    }
}
