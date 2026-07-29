-- ShedLock 锁表（组件 13，文档 7.7.2）：per-entity 库内 JDBC 锁存储
-- 生产环境多实例部署时保护 @Scheduled 防重；demo 单实例不实际竞争，配置示范为主
-- 严格分库下 per-entity 库内锁可用；跨实体共享的 global 任务需 Redis / K8s Lease（文档 5.2.6）
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
