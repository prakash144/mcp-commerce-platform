package com.commerce.order.event;

import com.commerce.events.OrderCancelled;
import com.commerce.events.OrderCreated;
import com.commerce.order.entity.Order;
import com.commerce.order.entity.OutboxEvent;
import com.commerce.order.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Writes saga facts into the transactional outbox. IMPORTANT: these methods MUST
 * run inside the same DB transaction that changes order state (the caller owns
 * the transaction). The outbox row is the durable record of intention — it keeps
 * the event even if the process dies before Kafka ever sees it.
 */
@Component
@RequiredArgsConstructor
public class OrderEventOutbox {

    private final OutboxRepository outboxRepository;

    public void recordOrderCreated(Order order) {
        OrderCreated event = OrderCreated.newBuilder()
                .setOrderId(order.getId().toString())
                .setCustomerId(order.getCustomerId())
                .setTotalMinor(order.getTotalAmount().movePointRight(2).longValueExact())
                .setCurrency(order.getCurrency())
                .setItemCount(order.getItems().size())
                .setOccurredAt(Instant.now().toString())
                .setCorrelationId(correlationId())
                .build();
        save(event, order.getId());
    }

    public void recordOrderCancelled(Order order, String reason) {
        OrderCancelled event = OrderCancelled.newBuilder()
                .setOrderId(order.getId().toString())
                .setReason(reason == null || reason.isBlank() ? "user_request" : reason)
                .setOccurredAt(Instant.now().toString())
                .setCorrelationId(correlationId())
                .build();
        save(event, order.getId());
    }

    private void save(org.apache.avro.specific.SpecificRecord event, UUID aggregateId) {
        String eventType = event.getSchema().getName();
        OutboxEvent row = OutboxEvent.builder()
                .aggregateId(aggregateId)
                .aggregateType("order")
                .eventType(eventType)
                .payload(OrderEvents.toJson(event))
                .build();
        outboxRepository.save(row);
    }

    private static String correlationId() {
        String fromMdc = MDC.get("correlationId");
        return fromMdc == null || fromMdc.isBlank() ? UUID.randomUUID().toString() : fromMdc;
    }
}