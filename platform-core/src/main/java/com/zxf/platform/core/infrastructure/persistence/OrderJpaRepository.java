package com.zxf.platform.core.infrastructure.persistence;

import com.zxf.platform.core.domain.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data JPA 委托接口：技术选型细节，隔离在 {@link OrderJpaAdapter} 内部（包私有）。 */
interface OrderJpaRepository extends JpaRepository<Order, Long> {
}
