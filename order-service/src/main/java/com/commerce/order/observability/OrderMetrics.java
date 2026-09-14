package com.commerce.order.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class OrderMetrics {

    private final MeterRegistry registry;

    public OrderMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void orderCreated(String status) {
        registry.counter("commerce.orders", "status", status).increment();
    }

    public void revenue(long amountMinor) {
        registry.counter("commerce_revenue_minor_total", "currency", "INR").increment(amountMinor);
    }

    public void paymentCharge(String outcome) {
        registry.counter("commerce_payment_charge_total", "outcome", outcome).increment();
    }
}