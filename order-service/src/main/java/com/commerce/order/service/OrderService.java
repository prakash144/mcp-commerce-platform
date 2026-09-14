package com.commerce.order.service;

import com.commerce.order.client.PaymentClient;
import com.commerce.order.client.ProductClient;
import com.commerce.order.config.CustomerContext;
import com.commerce.order.dto.CreateOrderInput;
import com.commerce.order.dto.OrderItemInput;
import com.commerce.order.dto.OrderItemOutput;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderMapper mapper;
    private final ProductClient productClient;
    private final PaymentClient paymentClient;
    private final CustomerContext customerContext;
    private final OrderMetrics orderMetrics;

    @Value("${commerce.order.default-customer-id}")
    private String defaultCustomerId;

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

        Order order = Order.builder()
                .customerId(resolveCustomer(input.getCustomerId()))
                .currency(normalizeCurrency(input.getCurrency()))
                .status(OrderStatus.PENDING)
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
            order.addItem(orderItem);
            total = total.add(detail.price().multiply(BigDecimal.valueOf(item.getQuantity())));
        }
        order.setTotalAmount(total);

        Order saved = orderRepository.save(order);
        MDC.put("orderId", saved.getId().toString());
        log.info("evt=order.persisted orderId={} total={} status=PENDING", saved.getId(), total);

        String idempotencyKey = UUID.randomUUID().toString();
        MDC.put("idempotencyKey", idempotencyKey);
        MDC.put("amountMinor", String.valueOf(total.movePointRight(2).longValueExact()));
        log.info("evt=order.charge.start orderId={} idempotencyKey={} amountMinor={} currency={}",
                saved.getId(), idempotencyKey, total.movePointRight(2).longValueExact(), saved.getCurrency());

        PaymentClient.PaymentResult result;
        try {
            result = paymentClient.charge(
                    new PaymentClient.ChargeRequest(
                            saved.getId(),
                            saved.getCustomerId(),
                            saved.getTotalAmount(),
                            saved.getCurrency(),
                            idempotencyKey,
                            PaymentClient.PaymentMethod.CARD
                    )
            );
            MDC.put("paymentStatus", result.status().name());

            if (result.status() == PaymentClient.PaymentStatus.SUCCESS) {
                saved.setStatus(OrderStatus.CONFIRMED);
                saved = orderRepository.save(saved);
                log.info("evt=order.confirmed orderId={} paymentId={}", saved.getId(), result.transactionId());
                orderMetrics.orderCreated("CONFIRMED");
                orderMetrics.revenue(total.movePointRight(2).longValueExact());
                orderMetrics.paymentCharge("captured");
            } else {
                log.warn("evt=order.payment.failed orderId={} paymentStatus={}", saved.getId(), result.status());
                orderMetrics.orderCreated("PENDING");
                orderMetrics.paymentCharge("failed");
            }
        } finally {
            MDC.remove("orderId");
            MDC.remove("idempotencyKey");
            MDC.remove("amountMinor");
            MDC.remove("paymentStatus");
        }

        return mapper.toOutput(saved);
    }

    public OrderOutput cancelOrder(UUID id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw new OrderValidationException("ORDER_ALREADY_CANCELLED",
                    "Order " + id + " is already cancelled");
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