package com.commerce.order.resolver;

import com.commerce.order.dto.OrderItemOutput;
import com.commerce.order.dto.OrderOutput;
import com.commerce.order.dto.OrderPageOutput;
import com.commerce.order.dto.OrderStatsOutput;
import com.commerce.order.entity.OrderStatus;
import com.commerce.order.service.OrderService;
import org.dataloader.DataLoader;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Controller
public class OrderQueryResolver {

    private final OrderService orderService;

    public OrderQueryResolver(OrderService orderService) {
        this.orderService = orderService;
    }

    @QueryMapping
    public OrderOutput order(@Argument UUID id) {
        return orderService.getOrder(id);
    }

    @QueryMapping
    public OrderPageOutput ordersByCustomer(@Argument String customerId,
                                            @Argument int first,
                                            @Argument int offset) {
        return orderService.getOrdersByCustomer(customerId, offset, first);
    }

    @QueryMapping
    public OrderPageOutput orders(@Argument int first,
                                  @Argument int offset,
                                  @Argument OrderStatus status) {
        return orderService.getOrders(status, offset, first);
    }

    @QueryMapping
    public OrderStatsOutput orderStats() {
        return orderService.getOrderStats();
    }

    @SchemaMapping(typeName = "Order", field = "items")
    public CompletableFuture<List<OrderItemOutput>> items(OrderOutput order,
                                                          DataLoader<UUID, List<OrderItemOutput>> loader) {
        return loader.load(order.getId());
    }
}