package com.commerce.order.service;

import com.commerce.order.client.PermanentPaymentException;
import com.commerce.order.client.PaymentClient;
import com.commerce.order.client.ProductClient;
import com.commerce.order.client.RecoverablePaymentException;
import com.commerce.order.config.CustomerContext;
import com.commerce.order.dto.CreateOrderInput;
import com.commerce.order.dto.OrderItemInput;
import com.commerce.order.dto.OrderOutput;
import com.commerce.order.dto.OrderPageOutput;
import com.commerce.order.dto.OrderStatsOutput;
import com.commerce.order.entity.Order;
import com.commerce.order.entity.OrderStatus;
import com.commerce.order.exception.OrderValidationException;
import com.commerce.order.event.OrderEventOutbox;
import com.commerce.order.mapper.OrderMapper;
import com.commerce.order.observability.OrderMetrics;
import com.commerce.order.repository.OrderItemRepository;
import com.commerce.order.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock private OrderRepository orderRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private OrderMapper mapper;
    @Mock private ProductClient productClient;
    @Mock private PaymentClient paymentClient;
    @Mock private CustomerContext customerContext;
    @Mock private OrderMetrics orderMetrics;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private TransactionStatus transactionStatus;
    @Mock private OrderEventOutbox orderEventOutbox;

    private final Map<UUID, Order> store = new HashMap<>();

    private OrderService orderService;

    private final UUID productId = UUID.randomUUID();
    private final BigDecimal price = new BigDecimal("19.99");

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        lenient().when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            if (o.getId() == null) {
                o.setId(UUID.randomUUID());
            }
            store.put(o.getId(), copyOf(o));
            return o;
        });
        lenient().when(orderRepository.findById(any(UUID.class)))
                .thenAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(0))));
        lenient().when(mapper.toOutput(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            return OrderOutput.builder()
                    .id(o.getId())
                    .customerId(o.getCustomerId())
                    .status(o.getStatus())
                    .totalAmount(o.getTotalAmount())
                    .currency(o.getCurrency())
                    .paymentId(o.getPaymentId())
                    .chargeAttempts(o.getChargeAttempts())
                    .build();
        });

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        orderService = new OrderService(
                orderRepository, orderItemRepository, mapper,
                productClient, paymentClient, customerContext,
                orderMetrics, orderEventOutbox, tx);
        ReflectionTestUtils.setField(orderService, "maxAttempts", 5);
        ReflectionTestUtils.setField(orderService, "initialBackoff", java.time.Duration.ofSeconds(30));
        ReflectionTestUtils.setField(orderService, "maxBackoff", java.time.Duration.ofMinutes(15));
    }

    private void seed(Order order) {
        store.put(order.getId(), copyOf(order));
    }

    private static Order copyOf(Order o) {
        return Order.builder()
                .id(o.getId())
                .customerId(o.getCustomerId())
                .status(o.getStatus())
                .totalAmount(o.getTotalAmount())
                .currency(o.getCurrency())
                .idempotencyKey(o.getIdempotencyKey())
                .paymentId(o.getPaymentId())
                .chargeAttempts(o.getChargeAttempts())
                .lastChargeError(o.getLastChargeError())
                .nextRetryAt(o.getNextRetryAt())
                .createdAt(o.getCreatedAt())
                .updatedAt(o.getUpdatedAt())
                .build();
    }

    private CreateOrderInput singleItem() {
        CreateOrderInput input = new CreateOrderInput();
        OrderItemInput item = new OrderItemInput();
        item.setProductId(productId);
        item.setQuantity(1);
        input.setItems(List.of(item));
        return input;
    }

    @Test
    void getOrdersWithoutStatusFetchesAllSortedDesc() {
        Order o1 = pending("1999.00");
        Order o2 = pending("899.00");
        when(orderRepository.findAll(any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(o1, o2)));
        when(mapper.toOutput(o1)).thenReturn(OrderOutput.builder().id(o1.getId()).status(o1.getStatus()).build());
        when(mapper.toOutput(o2)).thenReturn(OrderOutput.builder().id(o2.getId()).status(o2.getStatus()).build());

        OrderPageOutput result = orderService.getOrders(null, 0, 20);

        assertThat(result.getTotalCount()).isEqualTo(2);
        verify(orderRepository).findAll(any(PageRequest.class));
    }

    @Test
    void getOrdersWithStatusFetchesOnlyThatStatus() {
        Order o1 = pending("1999.00");
        when(orderRepository.findByStatus(eq(OrderStatus.CONFIRMED), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(o1)));
        when(mapper.toOutput(o1)).thenReturn(OrderOutput.builder().id(o1.getId()).status(o1.getStatus()).build());

        OrderPageOutput result = orderService.getOrders(OrderStatus.CONFIRMED, 2, 10);

        assertThat(result.getTotalCount()).isEqualTo(1);
        verify(orderRepository).findByStatus(eq(OrderStatus.CONFIRMED), any(PageRequest.class));
    }

    @Test
    void getOrderStatsAggregatesConfirmedRevenue() {
        when(orderRepository.countOrders()).thenReturn(16L);
        when(orderRepository.sumTotalByStatus(OrderStatus.CONFIRMED))
                .thenReturn(new BigDecimal("889.74"));

        OrderStatsOutput stats = orderService.getOrderStats();

        assertThat(stats.getTotalOrders()).isEqualTo(16);
        assertThat(stats.getRevenue()).isEqualByComparingTo(new BigDecimal("889.74"));
    }

    @Test
    void createOrderPersistsKeyBeforeChargeAndConfirmsOnSuccess() {
        CreateOrderInput input = singleItem();
        when(productClient.fetchByIds(any()))
                .thenReturn(List.of(new ProductClient.ProductDetail(productId, "Widget", price)));
        when(paymentClient.charge(any())).thenReturn(
                new PaymentClient.PaymentResult(PaymentClient.PaymentStatus.SUCCESS, "txn_ok"));

        orderService.createOrder(input);

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository, times(2)).save(orderCaptor.capture());
        Order persistedPending = orderCaptor.getAllValues().get(0);
        Order settled = orderCaptor.getAllValues().get(1);

        assertThat(persistedPending.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(persistedPending.getIdempotencyKey()).isNotBlank();
        assertThat(persistedPending.getChargeAttempts()).isEqualTo(0);

        assertThat(settled.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(settled.getPaymentId()).isEqualTo("txn_ok");
        assertThat(settled.getChargeAttempts()).isEqualTo(1);
        assertThat(settled.getNextRetryAt()).isNull();

        ArgumentCaptor<PaymentClient.ChargeRequest> chargeCaptor = ArgumentCaptor.forClass(PaymentClient.ChargeRequest.class);
        verify(paymentClient).charge(chargeCaptor.capture());
        assertThat(chargeCaptor.getValue().idempotencyKey())
                .isEqualTo(persistedPending.getIdempotencyKey());

        verify(orderMetrics).chargeAttempt("captured");
        verify(orderMetrics).paymentCharge("captured");
    }

    @Test
    void createOrderDeclinedPaymentMarksTerminalFailed() {
        CreateOrderInput input = singleItem();
        when(productClient.fetchByIds(any()))
                .thenReturn(List.of(new ProductClient.ProductDetail(productId, "Widget", price)));
        when(paymentClient.charge(any())).thenReturn(
                new PaymentClient.PaymentResult(PaymentClient.PaymentStatus.FAILED, null));

        orderService.createOrder(input);

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository, times(2)).save(orderCaptor.capture());
        Order settled = orderCaptor.getAllValues().get(1);

        assertThat(settled.getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(settled.getChargeAttempts()).isEqualTo(1);
        assertThat(settled.getNextRetryAt()).isNull();

        verify(orderMetrics).chargeAttempt("declined");
        verify(orderMetrics).paymentCharge("failed");
    }

    @Test
    void createOrderTransientFailureRecordsRetryAndPropagates() {
        CreateOrderInput input = singleItem();
        when(productClient.fetchByIds(any()))
                .thenReturn(List.of(new ProductClient.ProductDetail(productId, "Widget", price)));
        when(paymentClient.charge(any())).thenThrow(new RecoverablePaymentException("timeout", new RuntimeException()));

        assertThatThrownBy(() -> orderService.createOrder(input))
                .isInstanceOf(RecoverablePaymentException.class);

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository, times(2)).save(orderCaptor.capture());
        Order persisted = orderCaptor.getAllValues().get(1);

        assertThat(persisted.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(persisted.getChargeAttempts()).isEqualTo(1);
        assertThat(persisted.getNextRetryAt()).isNotNull();
        assertThat(persisted.getLastChargeError()).contains("RecoverablePaymentException");

        verify(orderMetrics).chargeAttempt("rescheduled");
    }

    @Test
    void createOrderPermanentFailureStillRecordsRetry() {
        CreateOrderInput input = singleItem();
        when(productClient.fetchByIds(any()))
                .thenReturn(List.of(new ProductClient.ProductDetail(productId, "Widget", price)));
        when(paymentClient.charge(any())).thenThrow(new PermanentPaymentException("invalid argument"));

        assertThatThrownBy(() -> orderService.createOrder(input))
                .isInstanceOf(PermanentPaymentException.class);

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository, times(2)).save(orderCaptor.capture());
        Order persisted = orderCaptor.getAllValues().get(1);

        assertThat(persisted.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(persisted.getChargeAttempts()).isEqualTo(1);
        assertThat(persisted.getNextRetryAt()).isNotNull();

        verify(orderMetrics).chargeAttempt("rescheduled");
    }

    @Test
    void retryAtMaxAttemptsMarksTerminalFailed() {
        UUID orderId = UUID.randomUUID();
        seed(Order.builder()
                .id(orderId)
                .customerId("cust")
                .currency("INR")
                .status(OrderStatus.PENDING)
                .totalAmount(price)
                .idempotencyKey(UUID.randomUUID().toString())
                .chargeAttempts(4)
                .build());
        when(paymentClient.charge(any())).thenThrow(new RecoverablePaymentException("boom", new RuntimeException()));

        assertThatThrownBy(() -> orderService.retryPending(orderId))
                .isInstanceOf(RecoverablePaymentException.class);

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(orderCaptor.capture());
        Order settled = orderCaptor.getValue();

        assertThat(settled.getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(settled.getChargeAttempts()).isEqualTo(5);
        assertThat(settled.getNextRetryAt()).isNull();

        verify(orderMetrics).chargeAttempt("exhausted");
    }

    @Test
    void retryPendingReusesIdempotencyKey() {
        UUID orderId = UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();
        seed(Order.builder()
                .id(orderId)
                .customerId("cust")
                .currency("INR")
                .status(OrderStatus.PENDING)
                .totalAmount(price)
                .idempotencyKey(idempotencyKey)
                .chargeAttempts(1)
                .build());
        when(paymentClient.charge(any())).thenReturn(
                new PaymentClient.PaymentResult(PaymentClient.PaymentStatus.SUCCESS, "txn_retry"));

        orderService.retryPending(orderId);

        ArgumentCaptor<PaymentClient.ChargeRequest> captor = ArgumentCaptor.forClass(PaymentClient.ChargeRequest.class);
        verify(paymentClient, times(1)).charge(captor.capture());
        assertThat(captor.getValue().idempotencyKey()).isEqualTo(idempotencyKey);
    }

    @Test
    void retryPendingSkipsNonPendingOrders() {
        UUID orderId = UUID.randomUUID();
        seed(Order.builder().id(orderId).status(OrderStatus.CONFIRMED).build());

        orderService.retryPending(orderId);

        verify(paymentClient, never()).charge(any());
        verify(orderRepository, never()).save(any());
    }

    @Test
    void cancelOrderRejectsConfirmed() {
        UUID orderId = UUID.randomUUID();
        seed(Order.builder().id(orderId).status(OrderStatus.CONFIRMED).build());

        assertThatThrownBy(() -> orderService.cancelOrder(orderId))
                .isInstanceOf(OrderValidationException.class)
                .hasMessageContaining("cannot be cancelled");
    }

    @Test
    void cancelOrderRejectsFailed() {
        UUID orderId = UUID.randomUUID();
        seed(Order.builder().id(orderId).status(OrderStatus.FAILED).build());

        assertThatThrownBy(() -> orderService.cancelOrder(orderId))
                .isInstanceOf(OrderValidationException.class)
                .hasMessageContaining("cannot be cancelled");
    }

    @Test
    void cancelOrderAllowsPending() {
        UUID orderId = UUID.randomUUID();
        seed(Order.builder().id(orderId).status(OrderStatus.PENDING).build());

        OrderOutput out = orderService.cancelOrder(orderId);

        assertThat(out.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    private static Order pending(String amount) {
        return Order.builder()
                .id(UUID.randomUUID())
                .customerId("cust")
                .currency("INR")
                .status(OrderStatus.PENDING)
                .totalAmount(new BigDecimal(amount))
                .build();
    }
}