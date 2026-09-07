package com.commerce.order.dto;

import com.commerce.order.entity.OrderStatus;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class OrderOutput {
    private UUID id;
    private String customerId;
    private OrderStatus status;
    private BigDecimal totalAmount;
    private String currency;
    private Instant createdAt;
    private Instant updatedAt;
}
