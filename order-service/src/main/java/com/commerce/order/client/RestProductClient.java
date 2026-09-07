package com.commerce.order.client;

import com.commerce.order.client.ProductClient.ProductDetail;
import com.commerce.order.exception.OrderValidationException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Component
public class RestProductClient implements ProductClient {

    private final RestClient restClient;

    public RestProductClient(RestClient productRestClient) {
        this.restClient = productRestClient;
    }

    @Override
    public List<ProductDetail> fetchByIds(Collection<UUID> productIds) {
        if (productIds.isEmpty()) {
            return List.of();
        }

        Map<UUID, ProductDetail> byId = new ConcurrentHashMap<>();
        List<String> failures = new CopyOnWriteArrayList<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (UUID id : productIds) {
                futures.add(executor.submit(() -> {
                    try {
                        byId.put(id, fetchOne(id));
                    } catch (RestClientException e) {
                        failures.add(id + ": " + e.getMessage());
                    }
                }));
            }

            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new OrderValidationException("PRODUCT_VALIDATION_FAILED",
                            "Product validation interrupted");
                } catch (ExecutionException e) {
                    throw new OrderValidationException("PRODUCT_VALIDATION_FAILED",
                            "Product validation failed: " + e.getCause().getMessage());
                }
            }
        }

        if (!failures.isEmpty()) {
            throw new OrderValidationException(
                    "PRODUCT_VALIDATION_FAILED",
                    "Unable to validate product(s). Ensure product-service is running.",
                    Map.of("productIds", List.copyOf(failures)));
        }
        return List.copyOf(byId.values());
    }

    private ProductDetail fetchOne(UUID id) {
        ProductPayload payload = restClient.get()
                .uri("/api/v1/products/{id}", id)
                .retrieve()
                .body(ProductPayload.class);
        if (payload == null) {
            throw new OrderValidationException("PRODUCT_VALIDATION_FAILED",
                    "Product with id " + id + " not found",
                    Map.of("productId", id.toString()));
        }
        return new ProductDetail(payload.id(), payload.name(), payload.price());
    }

    record ProductPayload(UUID id, String name, BigDecimal price) {
    }
}
