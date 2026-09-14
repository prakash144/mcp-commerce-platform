package com.commerce.order.config;

import com.commerce.order.client.PermanentPaymentException;
import com.commerce.order.client.RecoverablePaymentException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.functions.Either;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResilienceConfigTest {

    @Test
    void retryConfigRetriesOnlyRecoverableErrorsUpToThreeTimes() {
        Retry retry = new ResilienceConfig().paymentRetry(new SimpleMeterRegistry());
        RetryConfig cfg = retry.getRetryConfig();

        assertThat(cfg.getMaxAttempts()).isEqualTo(3);
        assertThat(cfg.getIntervalBiFunction().apply(1, Either.left(new RuntimeException()))).isEqualTo(300L);
        assertThat(cfg.getExceptionPredicate().test(new RecoverablePaymentException("boom", new RuntimeException())))
                .isTrue();
        assertThat(cfg.getExceptionPredicate().test(new PermanentPaymentException("nope"))).isFalse();
    }

    @Test
    void breakerIgnoresPermanentRejectionsAndOpensAfterThreeFailures() {
        CircuitBreaker breaker = new ResilienceConfig().paymentCircuitBreaker(new SimpleMeterRegistry());
        CircuitBreakerConfig cfg = breaker.getCircuitBreakerConfig();

        assertThat(cfg.getMinimumNumberOfCalls()).isEqualTo(3);
        assertThat(cfg.getFailureRateThreshold()).isEqualTo(50f);
        assertThat(cfg.getSlidingWindowSize()).isEqualTo(10);
        assertThat(cfg.getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(5000L);
        assertThat(cfg.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(3);
        assertThat(cfg.getIgnoreExceptionPredicate().test(new PermanentPaymentException("nope"))).isTrue();
        assertThat(cfg.getIgnoreExceptionPredicate().test(new RecoverablePaymentException("boom", new RuntimeException())))
                .isFalse();
    }
}