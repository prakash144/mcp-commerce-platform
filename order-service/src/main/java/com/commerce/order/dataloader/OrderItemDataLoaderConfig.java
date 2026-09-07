package com.commerce.order.dataloader;

import com.commerce.order.dto.OrderItemOutput;
import com.commerce.order.entity.OrderItem;
import com.commerce.order.mapper.OrderMapper;
import com.commerce.order.repository.OrderItemRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.execution.BatchLoaderRegistry;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;


@Configuration
public class OrderItemDataLoaderConfig {

    private final BatchLoaderRegistry registry;
    private final OrderItemRepository repository;
    private final OrderMapper mapper;

    public OrderItemDataLoaderConfig(BatchLoaderRegistry registry, OrderItemRepository repository, OrderMapper mapper) {
        this.registry = registry;
        this.repository = repository;
        this.mapper = mapper;
    }

    @PostConstruct
    public void registerOrderItemsLoader() {
        registry.forTypePair(UUID.class, List.class)
                .registerMappedBatchLoader((orderIds, env) ->
                        Mono.fromCallable(() -> loadItems(orderIds)));
    }

    private Map<UUID, List> loadItems(Set<UUID> orderIds) {
        Map<UUID, List<OrderItemOutput>> grouped = new HashMap<>();
        for (OrderItem item : repository.findByOrderIdIn(orderIds)) {
            grouped.computeIfAbsent(item.getOrder().getId(), k -> new ArrayList<>())
                    .add(mapper.toItemOutput(item));
        }
        Map<UUID, List> result = new HashMap<>();
        orderIds.forEach(id -> result.put(id, grouped.getOrDefault(id, List.of())));
        return result;
    }
}
