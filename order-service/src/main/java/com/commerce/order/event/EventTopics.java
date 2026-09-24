package com.commerce.order.event;

/**
 * Central naming for Kafka topics + consumer groups on the order side. Kept as a
 * single source of truth so a rename is one edit, not a grep.
 *
 * Naming convention: <domain>.<past-tense-fact> with key = orderId, so all events
 * for one order land in the same partition (per-order ordering). One value-schema
 * subject per topic (default SR naming strategy) registered via
 * scripts/register-schemas.sh.
 */
public final class EventTopics {

    private EventTopics() {
    }

    // Facts order-service publishes (via the outbox relay)
    public static final String ORDER_CREATED = "orders.created";
    public static final String ORDER_CANCELLED = "orders.cancelled";

    // Facts order-service consumes (published by payment-service)
    public static final String PAYMENT_SUCCEEDED = "payments.succeeded";
    public static final String PAYMENT_FAILED = "payments.failed";
    public static final String PAYMENT_VOIDED = "payments.voided";
    public static final String PAYMENT_REFUNDED = "payments.refunded";

    // Avro record names — the eventType stored in the outbox payload / as the
    // Kafka record. Must match the .avsc "name" attribute.
    public static final String TYPE_ORDER_CREATED = "OrderCreated";
    public static final String TYPE_ORDER_CANCELLED = "OrderCancelled";

    public static final String GROUP_ORDER_SAGA = "order-saga";

    /** Consumer-group per-topic DLQ topic name. */
    public static String dlqFor(String topic, String group) {
        return topic + "." + group + ".DLQ";
    }
}