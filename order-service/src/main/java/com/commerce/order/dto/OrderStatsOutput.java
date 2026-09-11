package com.commerce.order.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class OrderStatsOutput {
    private long totalOrders;
    private BigDecimal revenue;
}