package com.zxf.platform.core.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 文档 5.6：单测中的 EntityContext 直接 set/clear。 */
class EntityContextTest {

    @AfterEach
    void tearDown() {
        EntityContext.clear();
    }

    @Test
    void set后current可取() {
        EntityContext.set(EntityType.ALPHA);
        assertThat(EntityContext.current()).isEqualTo(EntityType.ALPHA);
        assertThat(EntityContext.currentOrNull()).isEqualTo(EntityType.ALPHA);
    }

    @Test
    void 未初始化时current抛异常而currentOrNull返回null() {
        assertThatThrownBy(EntityContext::current)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EntityContext 未初始化");
        assertThat(EntityContext.currentOrNull()).isNull();
    }

    @Test
    void clear后回到未初始化状态() {
        EntityContext.set(EntityType.BETA);
        EntityContext.clear();
        assertThat(EntityContext.currentOrNull()).isNull();
    }
}
