package com.commerce.order.config;

import com.commerce.order.client.PermanentPaymentException;
import com.commerce.order.client.RecoverablePaymentException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class ResilienceConfig {

    private static final Logger log = LoggerFactory.getLogger(ResilienceConfig.class);

    @Bean
    Retry paymentRetry(MeterRegistry registry) {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(300))
                .retryOnException(e -> e instanceof RecoverablePaymentException)
                .build();
        Retry retry = Retry.of("paymentCharge", config);
        Counter retries = registry.counter("commerce_retries_total", "name", "paymentCharge");
        retry.getEventPublisher().onRetry(e -> {
            retries.increment();
            log.warn("retrying {} attempt {} after {}", e.getName(), e.getNumberOfRetryAttempts(), e.getLastThrowable());
        });
        return retry;
    }

    @Bean
    CircuitBreaker paymentCircuitBreaker(MeterRegistry registry) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(3)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(5))
                .permittedNumberOfCallsInHalfOpenState(3)
                .ignoreExceptions(PermanentPaymentException.class)
                .build();
        CircuitBreaker cb = CircuitBreaker.of("paymentCharge", config);
        Tags tags = Tags.of("name", "paymentCharge");
        registry.gauge("commerce_circuitbreaker_state", tags, cb, b -> (double) b.getState().getOrder());
        cb.getEventPublisher().onStateTransition(e -> {
            CircuitBreaker.StateTransition t = e.getStateTransition();
            log.info("breaker {} -> {}", t.getFromState(), t.getToState());
        });
        return cb;
    }
}
