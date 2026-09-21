package com.commerce.order.event;

import com.commerce.events.PaymentFailed;
import com.commerce.events.PaymentRefunded;
import com.commerce.events.PaymentSucceeded;
import com.commerce.events.PaymentVoided;
import com.commerce.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Consumes the order lifecycle/reconciliation facts published by payment-service.
 * Every handler is IDEMPOTENT: it transitions the order with a guarded UPDATE
 * that only applies once (e.g. PENDING -> CONFIRMED), so at-least-once replays
 * are harmless. Logic failures bubble to the container error handler -> DLQ.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentEventConsumer {

    public static final String CORRELATION_HEADER = "correlationId";

    private final OrderService orderService;

    @KafkaListener(topics = OrderEvents.PAYMENTS_SUCCEEDED,
            groupId = OrderEvents.GROUP_ORDER_SAGA,
            containerFactory = "kafkaListenerContainerFactory")
    void onSucceeded(PaymentSucceeded event,
                     @Header(name = CORRELATION_HEADER, required = false) String correlationId) {
        withCorrelation(correlationId, () -> {
            orderService.handlePaymentSucceeded(
                    event.getOrderId().toString(), event.getPaymentId().toString());
        });
    }

    @KafkaListener(topics = OrderEvents.PAYMENTS_FAILED,
            groupId = OrderEvents.GROUP_ORDER_SAGA,
            containerFactory = "kafkaListenerContainerFactory")
    void onFailed(PaymentFailed event,
                  @Header(name = CORRELATION_HEADER, required = false) String correlationId) {
        withCorrelation(correlationId, () -> {
            orderService.handlePaymentFailed(
                    event.getOrderId().toString(), event.getErrorCode().toString());
        });
    }

    @KafkaListener(topics = OrderEvents.PAYMENTS_VOIDED,
            groupId = OrderEvents.GROUP_ORDER_SAGA,
            containerFactory = "kafkaListenerContainerFactory")
    void onVoided(PaymentVoided event,
                  @Header(name = CORRELATION_HEADER, required = false) String correlationId) {
        withCorrelation(correlationId, () ->
                orderService.handlePaymentVoided(event.getOrderId().toString()));
    }

    @KafkaListener(topics = OrderEvents.PAYMENTS_REFUNDED,
            groupId = OrderEvents.GROUP_ORDER_SAGA,
            containerFactory = "kafkaListenerContainerFactory")
    void onRefunded(PaymentRefunded event,
                    @Header(name = CORRELATION_HEADER, required = false) String correlationId) {
        withCorrelation(correlationId, () ->
                orderService.handlePaymentRefunded(event.getOrderId().toString()));
    }

    /**
     * Kafka is an async boundary — the correlation ID must travel with the event
     * (header), not via thread-local request state. Restore it into the MDC for
     * this consumer invocation so log lines stay joinable to the originating saga.
     */
    private void withCorrelation(String correlationId, Runnable action) {
        String corr = (correlationId == null || correlationId.isBlank())
                ? "kafka/" + java.util.UUID.randomUUID()
                : correlationId;
        MDC.put(CORRELATION_HEADER, corr);
        try {
            action.run();
        } finally {
            MDC.remove(CORRELATION_HEADER);
        }
    }
}