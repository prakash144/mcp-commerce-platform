package com.commerce.order.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class OrderItemInput {

    @NotNull
    private UUID productId;

    @NotNull
    @Min(1)
    @Max(999)
    private Integer quantity;
}
