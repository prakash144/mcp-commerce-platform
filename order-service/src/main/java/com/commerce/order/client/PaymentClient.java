package com.commerce.order.client;

import java.math.BigDecimal;
import java.util.UUID;

public interface PaymentClient {

    PaymentResult charge(ChargeRequest request);

    record ChargeRequest(UUID orderId, BigDecimal amount, String currency) {
    }

    record PaymentResult(PaymentStatus status, String transactionId) {
    }

    enum PaymentStatus {
        SUCCESS,
        FAILED
    }
}