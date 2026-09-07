package com.commerce.order.client;

import com.commerce.order.client.PaymentClient.PaymentResult;
import com.commerce.order.client.PaymentClient.PaymentStatus;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class StubPaymentClient implements PaymentClient {

    @Override
    public PaymentResult charge(ChargeRequest request) {
        return new PaymentResult(PaymentStatus.SUCCESS, "txn_" + UUID.randomUUID());
    }
}
