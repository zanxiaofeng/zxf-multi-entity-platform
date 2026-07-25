package com.zxf.platform.core.audit;

import com.zxf.platform.core.context.EntityContext;
import com.zxf.platform.core.context.EntityType;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 审计：共享内核的横切能力之一（文档 2.3）。
 *
 * <p>{@code @Async} 路径的实体上下文由 {@code TaskDecorator} 传播（文档 5.2.3），
 * 本类据此把实体维度写进审计记录与日志。
 *
 * <p>demo 用内存审计轨迹（便于测试断言）；生产替换为审计库 / 审计消息。
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final List<AuditEntry> trail = new CopyOnWriteArrayList<>();

    @Async
    public void record(String action, String detail) {
        // 上下文经 TaskDecorator 传播而来；基础设施场景允许缺失，用 currentOrNull
        EntityType entity = EntityContext.currentOrNull();
        trail.add(new AuditEntry(entity, action, detail));
        log.info("audit action={} detail={}", action, detail); // MDC 已带 entity 标
    }

    /** demo 用：返回审计轨迹快照。 */
    public List<AuditEntry> trail() {
        return List.copyOf(trail);
    }

    public record AuditEntry(@Nullable EntityType entity, String action, String detail) {
    }
}
