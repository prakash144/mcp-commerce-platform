package com.commerce.order.job;

import com.commerce.order.entity.Order;
import com.commerce.order.repository.OrderRepository;
import com.commerce.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Periodically re-attempts charging orders that are stuck in PENDING after a
 * transient payment failure. Every attempt reuses the persisted idempotency key,
 * so the payment service dedupes replays and an order is never charged twice.
 * Orders remain in PENDING until the attempt budget is exhausted (then FAILED).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PendingOrderRetryJob {

    private final OrderRepository orderRepository;
    private final OrderService orderService;

    @Value("${commerce.order.retry.poll-batch-size:20}")
    private int pollBatchSize = 20;

    @Scheduled(fixedDelayString = "${commerce.order.retry.poll-interval:15s}", initialDelayString = "${commerce.order.retry.initial-delay:15s}")
    public void retryDueOrders() {
        List<Order> due = orderRepository.findPendingDue(Instant.now(), PageRequest.of(0, pollBatchSize));
        if (due.isEmpty()) {
            return;
        }
        log.info("evt=order.retry.scan found={} pollBatchSize={}", due.size(), pollBatchSize);
        for (Order order : due) {
            try {
                orderService.retryPending(order.getId());
            } catch (RuntimeException ex) {
                log.warn("evt=order.retry.failed orderId={} error={}", order.getId(), ex.getMessage());
            }
        }
    }
}