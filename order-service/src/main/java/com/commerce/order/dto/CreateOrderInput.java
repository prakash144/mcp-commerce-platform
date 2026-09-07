package com.commerce.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class CreateOrderInput {

    @Valid
    @NotEmpty
    private List<OrderItemInput> items;

    private String customerId;

    private String currency = "USD";
}
