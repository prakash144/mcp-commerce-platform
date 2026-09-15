package com.commerce.order.service;

import com.commerce.order.client.PaymentClient;
import com.commerce.order.client.ProductClient;
import com.commerce.order.config.CustomerContext;
import com.commerce.order.dto.CreateOrderInput;
import com.commerce.order.dto.OrderItemInput;
import com.commerce.order.dto.OrderOutput;
import com.commerce.order.dto.OrderPageOutput;
import com.commerce.order.dto.OrderStatsOutput;
import com.commerce.order.entity.Order;
import com.commerce.order.entity.OrderItem;
import com.commerce.order.entity.OrderStatus;
import com.commerce.order.exception.OrderNotFoundException;
import com.commerce.order.exception.OrderValidationException;
import com.commerce.order.mapper.OrderMapper;
import com.commerce.order.observability.OrderMetrics;
import com.commerce.order.repository.OrderItemRepository;
import com.commerce.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderMapper mapper;
    private final ProductClient productClient;
    private final PaymentClient paymentClient;
    private final CustomerContext customerContext;
    private final OrderMetrics orderMetrics;
    private final TransactionTemplate tx;

    @Value("${commerce.order.default-customer-id}")
    private String defaultCustomerId;

    @Value("${commerce.order.retry.initial-backoff:30s}")
    private Duration initialBackoff;

    @Value("${commerce.order.retry.max-backoff:15m}")
    private Duration maxBackoff;

    @Value("${commerce.order.retry.max-attempts:5}")
    private int maxAttempts;

    public OrderOutput createOrder(CreateOrderInput input) {
        List<OrderItemInput> items = validateAndNormalize(input);

        log.info("evt=checkout.request customerId={} itemCount={} currency={}",
                input.getCustomerId() != null ? input.getCustomerId() : "default",
                items.size(),
                input.getCurrency() != null ? input.getCurrency() : "INR");

        Map<UUID, ProductClient.ProductDetail> products = productClient.fetchByIds(
                        items.stream().map(OrderItemInput::getProductId).toList())
                .stream()
                .collect(Collectors.toMap(ProductClient.ProductDetail::id, Function.identity()));

        String idempotencyKey = UUID.randomUUID().toString();
        Order saved = persistPendingOrder(input, items, products, idempotencyKey);

        MDC.put("orderId", saved.getId().toString());
        MDC.put("idempotencyKey", idempotencyKey);
        try {
            chargeAndSettle(saved.getId());
        } finally {
            MDC.remove("orderId");
            MDC.remove("idempotencyKey");
            MDC.remove("amountMinor");
            MDC.remove("paymentStatus");
        }

        return mapper.toOutput(saved);
    }

    /**
     * Persists the order in PENDING state together with its idempotency key in a
     * committed transaction, before any payment traffic. Every subsequent charge
     * attempt (synchronous or from the retry job) reuses this exact key, so the
     * payment service dedupes replays and an order can never be charged twice.
     */
    private Order persistPendingOrder(CreateOrderInput input, List<OrderItemInput> items,
                                      Map<UUID, ProductClient.ProductDetail> products,
                                      String idempotencyKey) {
        Order base = Order.builder()
                .customerId(resolveCustomer(input.getCustomerId()))
                .currency(normalizeCurrency(input.getCurrency()))
                .status(OrderStatus.PENDING)
                .idempotencyKey(idempotencyKey)
                .chargeAttempts(0)
                .build();

        BigDecimal total = BigDecimal.ZERO;
        for (OrderItemInput item : items) {
            ProductClient.ProductDetail detail = products.get(item.getProductId());
            OrderItem orderItem = OrderItem.builder()
                    .productId(item.getProductId())
                    .productName(detail.name())
                    .unitPrice(detail.price())
                    .quantity(item.getQuantity())
                    .build();
            base.addItem(orderItem);
            total = total.add(detail.price().multiply(BigDecimal.valueOf(item.getQuantity())));
        }
        base.setTotalAmount(total);
        BigDecimal finalTotal = total;

        return tx.execute(status -> {
            Order saved = orderRepository.save(base);
            log.info("evt=order.persisted orderId={} total={} status=PENDING idempotencyKey={}",
                    saved.getId(), finalTotal, idempotencyKey);
            return saved;
        });
    }

    /**
     * The single idempotent settlement path shared by createOrder and the retry
     * job. Reads the persisted idempotency key, charges, and records the outcome
     * in its own committed transaction. The payment call itself is never inside a
     * DB transaction, so a transient charge failure updates PENDING state (next
     * retry time) without rolling back the retry intent.
     */
    private void chargeAndSettle(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        if (order.getStatus() != OrderStatus.PENDING) {
            log.info("evt=order.charge.skipped orderId={} status={}", orderId, order.getStatus());
            return;
        }

        MDC.put("orderId", order.getId().toString());
        MDC.put("idempotencyKey", order.getIdempotencyKey());
        MDC.put("amountMinor", String.valueOf(order.getTotalAmount().movePointRight(2).longValueExact()));
        int attempt = order.getChargeAttempts() + 1;
        boolean isRetry = order.getChargeAttempts() > 0;

        log.info("evt=order.charge.start orderId={} attempt={} idempotencyKey={} amountMinor={} currency={}",
                order.getId(), attempt, order.getIdempotencyKey(),
                order.getTotalAmount().movePointRight(2).longValueExact(), order.getCurrency());

        PaymentClient.PaymentResult result;
        try {
            result = paymentClient.charge(
                    new PaymentClient.ChargeRequest(
                            order.getId(),
                            order.getCustomerId(),
                            order.getTotalAmount(),
                            order.getCurrency(),
                            order.getIdempotencyKey(),
                            PaymentClient.PaymentMethod.CARD
                    )
            );
        } catch (RuntimeException ex) {
            orderMetrics.chargeAttempt("rescheduled");
            if (attempt >= maxAttempts) {
                markTerminal(order, OrderStatus.FAILED, attempt, message(ex), null);
                orderMetrics.chargeAttempt("exhausted");
                log.warn("evt=order.charge.exhausted orderId={} attempts={} error={}",
                        order.getId(), attempt, message(ex));
            } else {
                Instant nextRetry = Instant.now().plus(backoff(attempt));
                recordChargeFailure(order, attempt, message(ex), nextRetry);
                log.warn("evt=order.charge.retryable orderId={} attempt={} nextRetryAt={} error={}",
                        order.getId(), attempt, nextRetry, message(ex));
            }
            throw ex;
        } finally {
            MDC.remove("orderId");
            MDC.remove("idempotencyKey");
            MDC.remove("amountMinor");
            MDC.remove("paymentStatus");
        }

        MDC.put("paymentStatus", result.status().name());
        if (result.status() == PaymentClient.PaymentStatus.SUCCESS) {
            markSettled(order, attempt, result.transactionId());
            orderMetrics.chargeAttempt(isRetry ? "captured_retried" : "captured");
            orderMetrics.orderCreated("CONFIRMED");
            orderMetrics.revenue(order.getTotalAmount().movePointRight(2).longValueExact());
            orderMetrics.paymentCharge("captured");
            log.info("evt=order.confirmed orderId={} attempt={} paymentId={}",
                    order.getId(), attempt, result.transactionId());
        } else {
            markTerminal(order, OrderStatus.FAILED, attempt,
                    "payment declined: " + result.status(), null);
            orderMetrics.chargeAttempt("declined");
            orderMetrics.orderCreated("FAILED");
            orderMetrics.paymentCharge("failed");
            log.warn("evt=order.payment.failed orderId={} attempt={} paymentStatus={}",
                    order.getId(), attempt, result.status());
        }
    }

    public OrderOutput retryPending(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        if (order.getStatus() != OrderStatus.PENDING) {
            return mapper.toOutput(order);
        }
        chargeAndSettle(orderId);
        return mapper.toOutput(orderRepository.findById(orderId).orElse(order));
    }

    private void markSettled(Order order, int attempt, String paymentId) {
        tx.executeWithoutResult(status -> {
            Order current = orderRepository.findById(order.getId()).orElse(order);
            current.setStatus(OrderStatus.CONFIRMED);
            current.setPaymentId(paymentId);
            current.setChargeAttempts(attempt);
            current.setLastChargeError(null);
            current.setNextRetryAt(null);
            orderRepository.save(current);
        });
    }

    private void recordChargeFailure(Order order, int attempt, String error, Instant nextRetryAt) {
        tx.executeWithoutResult(status -> {
            Order current = orderRepository.findById(order.getId()).orElse(order);
            current.setChargeAttempts(attempt);
            current.setLastChargeError(truncate(error));
            current.setNextRetryAt(nextRetryAt);
            orderRepository.save(current);
        });
    }

    private void markTerminal(Order order, OrderStatus terminal, int attempt, String error, String paymentId) {
        tx.executeWithoutResult(status -> {
            Order current = orderRepository.findById(order.getId()).orElse(order);
            current.setStatus(terminal);
            current.setChargeAttempts(attempt);
            current.setLastChargeError(truncate(error));
            current.setNextRetryAt(null);
            current.setPaymentId(paymentId);
            orderRepository.save(current);
        });
    }

    private Duration backoff(int attempt) {
        long base = initialBackoff.toMillis();
        long cap = maxBackoff.toMillis();
        if (attempt - 1 >= 31) {
            return Duration.ofMillis(cap);
        }
        long exp = base * (1L << (attempt - 1));
        return Duration.ofMillis(Math.min(exp, cap));
    }

    private String message(RuntimeException ex) {
        return ex.getClass().getSimpleName() + ": " + ex.getMessage();
    }

    private String truncate(String s) {
        if (s == null || s.length() <= 255) {
            return s;
        }
        return s.substring(0, 255);
    }

    @Transactional
    public OrderOutput cancelOrder(UUID id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw new OrderValidationException("ORDER_ALREADY_CANCELLED",
                    "Order " + id + " is already cancelled");
        }
        if (order.getStatus() == OrderStatus.CONFIRMED || order.getStatus() == OrderStatus.FAILED) {
            throw new OrderValidationException("ORDER_NOT_CANCELLABLE",
                    "Order " + id + " is " + order.getStatus() + " and cannot be cancelled");
        }
        order.setStatus(OrderStatus.CANCELLED);
        return mapper.toOutput(orderRepository.save(order));
    }

    @Transactional(readOnly = true)
    public OrderOutput getOrder(UUID id) {
        return mapper.toOutput(orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id)));
    }

    @Transactional(readOnly = true)
    public OrderPageOutput getOrdersByCustomer(String customerId, int offset, int limit) {
        String resolved = (customerId == null || customerId.isBlank())
                ? defaultCustomerId
                : customerId;
        Page<Order> page = orderRepository.findByCustomerId(resolved,
                PageRequest.of(offset, limit, Sort.by(Sort.Direction.DESC, "createdAt")));
        return OrderPageOutput.builder()
                .orders(page.getContent().stream().map(mapper::toOutput).toList())
                .totalCount(page.getTotalElements())
                .offset(offset)
                .limit(limit)
                .build();
    }

    @Transactional(readOnly = true)
    public OrderPageOutput getOrders(OrderStatus status, int offset, int limit) {
        Pageable pageable = PageRequest.of(offset, limit, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Order> page = (status == null)
                ? orderRepository.findAll(pageable)
                : orderRepository.findByStatus(status, pageable);
        return OrderPageOutput.builder()
                .orders(page.getContent().stream().map(mapper::toOutput).toList())
                .totalCount(page.getTotalElements())
                .offset(offset)
                .limit(limit)
                .build();
    }

    @Transactional(readOnly = true)
    public OrderStatsOutput getOrderStats() {
        return OrderStatsOutput.builder()
                .totalOrders(orderRepository.countOrders())
                .revenue(orderRepository.sumTotalByStatus(OrderStatus.CONFIRMED))
                .build();
    }

    private List<OrderItemInput> validateAndNormalize(CreateOrderInput input) {
        if (input.getItems() == null || input.getItems().isEmpty()) {
            throw new OrderValidationException("EMPTY_ORDER", "Order must contain at least one item");
        }
        for (OrderItemInput item : input.getItems()) {
            if (item.getProductId() == null) {
                throw new OrderValidationException("INVALID_ITEM", "productId is required for every item");
            }
            if (item.getQuantity() == null || item.getQuantity() < 1 || item.getQuantity() > 999) {
                throw new OrderValidationException("INVALID_QUANTITY", "quantity must be between 1 and 999");
            }
        }
        return input.getItems();
    }

    private String resolveCustomer(String requested) {
        String fromHeader = customerContext.customerIdFromHeader();
        if (fromHeader != null && !fromHeader.isBlank()) {
            return fromHeader;
        }
        if (requested != null && !requested.isBlank()) {
            return requested;
        }
        return defaultCustomerId;
    }

    private String normalizeCurrency(String currency) {
        String c = (currency == null || currency.isBlank()) ? "USD" : currency.toUpperCase(Locale.ROOT);
        if (!c.matches("[A-Z]{3}")) {
            throw new OrderValidationException("INVALID_CURRENCY",
                    "Currency must be a 3-letter ISO code, got '" + currency + "'");
        }
        return c;
    }
}