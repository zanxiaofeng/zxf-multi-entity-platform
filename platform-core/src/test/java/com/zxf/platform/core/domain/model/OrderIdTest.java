package com.zxf.platform.core.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 文档 5.9 军规 3：标识值对象的契约。 */
class OrderIdTest {

    @Test
    void 数据库主键包装为标识() {
        assertThat(OrderId.of(42L).value()).isEqualTo("42");
    }

    @Test
    void 空白标识违反构造契约() {
        assertThatThrownBy(() -> new OrderId(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderId(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
