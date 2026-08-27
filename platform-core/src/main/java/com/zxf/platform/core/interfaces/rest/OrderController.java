package com.zxf.platform.core.interfaces.rest;

import com.zxf.platform.core.application.OrderApplicationService;
import com.zxf.platform.core.application.assembler.OrderResponse;
import com.zxf.platform.core.application.command.CreateOrderCommand;
import com.zxf.platform.core.domain.exception.OrderNotFoundException;
import com.zxf.platform.core.domain.model.OrderId;
import java.net.URI;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单 REST 入口：两个实体部署共用同一契约（文档 6.2 API 契约治理）。
 * 控制器内同样不允许出现实体判断——差异已在应用层委托扩展点。
 */
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderApplicationService service;

    /**
     * 创建订单。
     *
     * @param cmd 创建命令（{@code @Valid} 触发 Bean Validation，非法 → 400）
     * @return 201 + {@code Location} 头指向新订单
     */
    @PostMapping
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderCommand cmd) {
        var order = service.create(cmd);
        return ResponseEntity.created(URI.create("/api/v1/orders/" + order.id().value()))
                .body(OrderResponse.from(order));
    }

    /**
     * 按标识查询订单。
     *
     * @param id 订单标识（{@code @Positive} 拦截非正数 → 400，api-conventions）
     * @return 订单响应；不存在时抛 {@link OrderNotFoundException}（handler 映射 404）
     */
    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable @Positive long id) {
        var orderId = OrderId.of(id);
        return service.findById(orderId)
                .map(OrderResponse::from)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }
}
