package com.commerce.order.client;

import com.commerce.payment.v1.Payment;
import com.commerce.payment.v1.PaymentServiceGrpc;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import org.slf4j.MDC;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.commerce.order.client.PaymentClient.ChargeRequest;
import com.commerce.order.client.PaymentClient.PaymentResult;

@Component
@Primary
public class GrpcPaymentClient implements PaymentClient {

    private static final long CALL_DEADLINE_MS = 3000;

    private final PaymentServiceGrpc.PaymentServiceBlockingStub stub;
    private final Retry paymentRetry;
    private final CircuitBreaker paymentCircuitBreaker;

    public GrpcPaymentClient(ManagedChannel paymentChannel, Retry paymentRetry, CircuitBreaker paymentCircuitBreaker) {
        this.stub = PaymentServiceGrpc.newBlockingStub(paymentChannel);
        this.paymentRetry = paymentRetry;
        this.paymentCircuitBreaker = paymentCircuitBreaker;
    }

    @Override
    public PaymentResult charge(ChargeRequest request) {
        Supplier<PaymentResult> call = CircuitBreaker.decorateSupplier(
                paymentCircuitBreaker,
                () -> Retry.decorateSupplier(paymentRetry, () -> doCharge(request)).get());
        return call.get();
    }

    private PaymentResult doCharge(ChargeRequest request) {
        Metadata md = new Metadata();
        String correlationId = MDC.get("correlationId");
        if (correlationId != null && !correlationId.isBlank()) {
            md.put(Metadata.Key.of("correlation-id", Metadata.ASCII_STRING_MARSHALLER), correlationId);
        }
        PaymentServiceGrpc.PaymentServiceBlockingStub correlated =
                stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(md));

        var protoRequest = com.commerce.payment.v1.ChargeRequest.newBuilder()
                .setIdempotencyKey(request.idempotencyKey())
                .setOrderId(request.orderId().toString())
                .setCustomerId(request.customerId())
                .setAmountMinor(toMinorUnits(request.amount()))
                .setCurrency(request.currency())
                .setMethod(toProtoMethod(request.method()))
                .build();

        Payment payment;
        try {
            payment = correlated.withDeadlineAfter(CALL_DEADLINE_MS, TimeUnit.MILLISECONDS)
                    .charge(protoRequest)
                    .getPayment();
        } catch (StatusRuntimeException e) {
            Status.Code code = e.getStatus().getCode();
            if (code == Status.Code.INVALID_ARGUMENT
                    || code == Status.Code.FAILED_PRECONDITION
                    || code == Status.Code.NOT_FOUND) {
                throw new PermanentPaymentException("payment rejected: " + e.getStatus().getDescription());
            }
            throw new RecoverablePaymentException("payment call failed: " + e.getStatus(), e);
        }

        if (payment.getStatus() == com.commerce.payment.v1.PaymentStatus.PAYMENT_STATUS_CAPTURED) {
            return new PaymentResult(PaymentClient.PaymentStatus.SUCCESS, payment.getId());
        }
        return new PaymentResult(PaymentClient.PaymentStatus.FAILED, payment.getId());
    }

    private long toMinorUnits(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be a positive decimal");
        }
        return amount.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
    }

    private com.commerce.payment.v1.PaymentMethod toProtoMethod(PaymentClient.PaymentMethod method) {
        return switch (method) {
            case CARD -> com.commerce.payment.v1.PaymentMethod.PAYMENT_METHOD_CARD;
            case BANK_TRANSFER -> com.commerce.payment.v1.PaymentMethod.PAYMENT_METHOD_BANK_TRANSFER;
            case WALLET -> com.commerce.payment.v1.PaymentMethod.PAYMENT_METHOD_WALLET;
        };
    }
}
