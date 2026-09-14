package com.commerce.order.client;

import com.commerce.payment.v1.ChargeRequest;
import com.commerce.payment.v1.ChargeResponse;
import com.commerce.payment.v1.Payment;
import com.commerce.payment.v1.PaymentServiceGrpc;
import com.commerce.payment.v1.PaymentStatus;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GrpcPaymentClientTest {

    private static final long CALL_DEADLINE_MS = 3000;

    private PaymentServiceImpl impl;
    private ManagedChannel channel;
    private io.grpc.Server server;
    private ExecutorService serverExecutor;
    private SimpleMeterRegistry meters;
    private Retry retry;
    private CircuitBreaker breaker;
    private GrpcPaymentClient client;

    private static final class PaymentServiceImpl extends PaymentServiceGrpc.PaymentServiceImplBase {
        final AtomicInteger chargeCalls = new AtomicInteger();
        volatile Status failWith;
        volatile int remainingFailures;
        volatile boolean decline;
        volatile long blockAfterFirstMs;

        @Override
        public void charge(ChargeRequest request, StreamObserver<ChargeResponse> observer) {
            int call = chargeCalls.incrementAndGet();
            if (blockAfterFirstMs > 0 && call == 1) {
                try {
                    Thread.sleep(blockAfterFirstMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (failWith != null) {
                observer.onError(failWith.asRuntimeException());
                return;
            }
            if (remainingFailures > 0 && call <= remainingFailures) {
                observer.onError(Status.UNAVAILABLE.asRuntimeException());
                return;
            }
            PaymentStatus status = decline
                    ? PaymentStatus.PAYMENT_STATUS_FAILED
                    : PaymentStatus.PAYMENT_STATUS_CAPTURED;
            Payment p = Payment.newBuilder().setId(UUID.randomUUID().toString()).setStatus(status).build();
            observer.onNext(ChargeResponse.newBuilder().setPayment(p).build());
            observer.onCompleted();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        impl = new PaymentServiceImpl();
        serverExecutor = Executors.newFixedThreadPool(4);
        meters = new SimpleMeterRegistry();
        retry = Retry.of("paymentCharge", RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(50))
                .retryOnException(e -> e instanceof RecoverablePaymentException)
                .build());
        retry.getEventPublisher().onRetry(e ->
                meters.counter("commerce_retries_total", "name", "paymentCharge").increment());
        breaker = CircuitBreaker.of("paymentCharge", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(3)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMillis(500))
                .permittedNumberOfCallsInHalfOpenState(3)
                .ignoreExceptions(PermanentPaymentException.class)
                .build());
        server = InProcessServerBuilder.forName(getClass().getName())
                .addService(impl)
                .executor(serverExecutor)
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(getClass().getName()).build();
        client = new GrpcPaymentClient(channel, retry, breaker);
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
        awaitTermination(channel);
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
        server.shutdownNow();
    }

    private static void awaitTermination(ManagedChannel c) {
        try {
            c.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private PaymentClient.ChargeRequest chargeRequest() {
        return new PaymentClient.ChargeRequest(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "customer-1",
                new BigDecimal("19.99"),
                "INR",
                "ik-" + UUID.randomUUID(),
                PaymentClient.PaymentMethod.CARD);
    }

    private long retryCounter() {
        Counter c = meters.find("commerce_retries_total").counter();
        return c == null ? 0 : (long) c.count();
    }

    @Test
    void recoverableErrorsAreRetriedUntilSuccess() {
        impl.remainingFailures = 2;

        PaymentClient.PaymentResult result = client.charge(chargeRequest());

        assertThat(result.status()).isEqualTo(PaymentClient.PaymentStatus.SUCCESS);
        assertThat(result.transactionId()).isNotBlank();
        assertThat(impl.chargeCalls.get()).isEqualTo(3);
        assertThat(retryCounter()).isEqualTo(2);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
    }

    @Test
    void permanentRejectionsAreNotRetriedAndIgnoredByBreaker() {
        for (Status permanent : new Status[]{Status.INVALID_ARGUMENT, Status.FAILED_PRECONDITION, Status.NOT_FOUND}) {
            impl.failWith = permanent;
            impl.chargeCalls.set(0);
            int before = (int) retryCounter();

            assertThatThrownBy(() -> client.charge(chargeRequest()))
                    .isInstanceOf(PermanentPaymentException.class);

            assertThat(impl.chargeCalls.get()).as("no retries for %s", permanent).isEqualTo(1);
            assertThat(retryCounter()).isEqualTo(before);
        }
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(breaker.getMetrics().getNumberOfNotPermittedCalls()).isZero();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.tryAcquirePermission()).as("breaker still closed after permanent rejections").isTrue();
    }

    @Test
    void deadlineIsIsolatedPerAttempt() {
        impl.blockAfterFirstMs = CALL_DEADLINE_MS + 2000;

        long start = System.nanoTime();
        PaymentClient.PaymentResult result = client.charge(chargeRequest());
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(result.status()).isEqualTo(PaymentClient.PaymentStatus.SUCCESS);
        assertThat(impl.chargeCalls.get()).isEqualTo(2);
        assertThat(elapsedMs)
                .as("first attempt hit its own 3s deadline, second got a fresh deadline, not a cumulative timeout")
                .isGreaterThanOrEqualTo(CALL_DEADLINE_MS - 200)
                .isLessThan(CALL_DEADLINE_MS * 2 + 1000);
    }

    @Test
    void exhaustedRecoverableErrorsOpenBreakerThenHalfOpenRecovers() throws Exception {
        impl.remainingFailures = Integer.MAX_VALUE;

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> client.charge(chargeRequest()))
                    .isInstanceOf(RecoverablePaymentException.class);
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        int attemptsWhileOpen = impl.chargeCalls.get();
        assertThat(retryCounter()).isEqualTo(6);

        long deadlineCheck = System.nanoTime();
        assertThatThrownBy(() -> client.charge(chargeRequest()))
                .isInstanceOf(CallNotPermittedException.class);
        long openShortCircuitMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - deadlineCheck);
        assertThat(impl.chargeCalls.get())
                .as("open breaker must not reach the gRPC server")
                .isEqualTo(attemptsWhileOpen);
        assertThat(openShortCircuitMs).isLessThan(500);

        Thread.sleep(700);
        impl.remainingFailures = 0;

        PaymentClient.PaymentResult result = client.charge(chargeRequest());

        assertThat(result.status()).isEqualTo(PaymentClient.PaymentStatus.SUCCESS);
        assertThat(breaker.getState())
                .as("first success in half-open transitions it to HALF_OPEN")
                .isEqualTo(CircuitBreaker.State.HALF_OPEN);

        client.charge(chargeRequest());
        client.charge(chargeRequest());

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void declinedPaymentReturnsFailedWithoutException() {
        impl.decline = true;

        PaymentClient.PaymentResult result = client.charge(chargeRequest());

        assertThat(result.status()).isEqualTo(PaymentClient.PaymentStatus.FAILED);
        assertThat(impl.chargeCalls.get()).isEqualTo(1);
        assertThat(retryCounter()).isZero();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.getMetrics().getNumberOfSuccessfulCalls()).as("business decline is not a transport failure")
                .isEqualTo(1);
    }
}