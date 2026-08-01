package com.zxf.platform.core.infrastructure.persistence;

import com.zxf.platform.core.domain.model.OutboxEvent;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Spring Data JPA 委托接口：技术选型细节，隔离在 {@link OutboxEventJpaAdapter} 内部（包私有）。
 *
 * <p>{@code findUnpublished} 用 {@code Pageable} 限制条数——JPQL 自带 {@code ORDER BY}，
 * 页大小即 {@code limit}；避免数据库方言差异（MySQL/H2 的 {@code LIMIT} 关键字行为不一致）。
 */
interface OutboxEventJpaRepository extends JpaRepository<OutboxEvent, Long> {

    @Query("SELECT e FROM OutboxEvent e WHERE e.publishedAt IS NULL ORDER BY e.createdAt")
    List<OutboxEvent> findUnpublished(Pageable pageable);
}
