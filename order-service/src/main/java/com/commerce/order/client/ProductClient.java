package com.commerce.order.client;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;


public interface ProductClient {
    List<ProductDetail> fetchByIds(Collection<UUID> productIds);
    record ProductDetail(UUID id, String name, BigDecimal price) {
    }
}
