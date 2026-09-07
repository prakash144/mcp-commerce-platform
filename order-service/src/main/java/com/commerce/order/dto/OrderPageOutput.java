package com.commerce.order.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class OrderPageOutput {
    private List<OrderOutput> orders;
    private long totalCount;
    private int offset;
    private int limit;
}
