package com.zxf.platform.core.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 文档 5.2.2：上下文设置与 MDC 打标必须在同一 try/finally 生命周期内——
 * 链条内可见，链条结束后彻底清理（Tomcat 线程池复用下防串实体）。
 */
class EntityContextFilterTest {

    private final EntityContextFilter filter =
            new EntityContextFilter(new PlatformProperties(EntityType.ALPHA));

    @Test
    void 请求内上下文与MDC可见且请求后清理() throws Exception {
        var seenContext = new AtomicReference<EntityType>();
        var seenMdc = new AtomicReference<String>();

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), (req, res) -> {
            seenContext.set(EntityContext.current());
            seenMdc.set(MDC.get("entity"));
        });

        assertThat(seenContext.get()).isEqualTo(EntityType.ALPHA);
        assertThat(seenMdc.get()).isEqualTo("ALPHA");
        // finally 已清理：线程复用后不得残留上一个实体的痕迹
        assertThat(EntityContext.currentOrNull()).isNull();
        assertThat(MDC.get("entity")).isNull();
    }

    @Test
    void 链条抛异常时仍然清理() {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        filter.doFilter(request, response, (req, res) -> {
                            throw new RuntimeException("boom");
                        }))
                .isInstanceOf(RuntimeException.class);

        assertThat(EntityContext.currentOrNull()).isNull();
        assertThat(MDC.get("entity")).isNull();
    }
}
