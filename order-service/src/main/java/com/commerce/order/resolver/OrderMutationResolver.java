package com.commerce.order.resolver;

import com.commerce.order.dto.CreateOrderInput;
import com.commerce.order.dto.OrderOutput;
import com.commerce.order.service.OrderService;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.stereotype.Controller;

import java.util.UUID;

@Controller
public class OrderMutationResolver {

    private final OrderService orderService;

    public OrderMutationResolver(OrderService orderService) {
        this.orderService = orderService;
    }

    @MutationMapping
    public OrderOutput createOrder(@Argument CreateOrderInput input) {
        return orderService.createOrder(input);
    }

    @MutationMapping
    public OrderOutput cancelOrder(@Argument UUID id) {
        return orderService.cancelOrder(id);
    }
}
