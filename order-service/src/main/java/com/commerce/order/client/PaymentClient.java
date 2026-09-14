package com.commerce.order.client;

import java.math.BigDecimal;
import java.util.UUID;

public interface PaymentClient {

    PaymentResult charge(ChargeRequest request);

    record ChargeRequest(UUID orderId, String customerId, BigDecimal amount, String currency,
                         String idempotencyKey, PaymentMethod method) {
    }

    record PaymentResult(PaymentStatus status, String transactionId) {
    }

    enum PaymentStatus {
        SUCCESS,
        FAILED
    }

    enum PaymentMethod {
       CARD,
       BANK_TRANSFER,
       WALLET
    }
}