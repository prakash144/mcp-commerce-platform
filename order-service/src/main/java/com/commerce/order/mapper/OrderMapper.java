package com.commerce.order.mapper;

import com.commerce.order.dto.OrderItemOutput;
import com.commerce.order.dto.OrderOutput;
import com.commerce.order.entity.Order;
import com.commerce.order.entity.OrderItem;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface OrderMapper {
    OrderOutput toOutput(Order order);

    OrderItemOutput toItemOutput(OrderItem orderItem);
}
