package com.commerce.order.service;

import com.commerce.order.config.CustomerContext;
import com.commerce.order.client.PaymentClient;
import com.commerce.order.client.ProductClient;
import com.commerce.order.entity.Order;
import com.commerce.order.entity.OrderItem;
import com.commerce.order.entity.OrderStatus;
import com.commerce.order.mapper.OrderMapper;
import com.commerce.order.observability.OrderMetrics;
import com.commerce.order.repository.OrderItemRepository;
import com.commerce.order.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.commerce.order.dto.OrderOutput;
import com.commerce.order.dto.OrderPageOutput;
import com.commerce.order.dto.OrderStatsOutput;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private OrderMapper mapper;
    @Mock
    private ProductClient productClient;
    @Mock
    private PaymentClient paymentClient;
    @Mock
    private CustomerContext customerContext;
    @Mock
    private OrderMetrics orderMetrics;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderRepository, orderItemRepository, mapper,
                productClient, paymentClient, customerContext, orderMetrics);
    }

    private Order order(OrderStatus status, BigDecimal amount) {
        return Order.builder()
                .id(UUID.randomUUID())
                .customerId("11111111-1111-1111-1111-111111111111")
                .currency("INR")
                .status(status)
                .totalAmount(amount)
                .items(List.of(OrderItem.builder().build()))
                .build();
    }

    private OrderOutput outputFor(Order o) {
        return OrderOutput.builder()
                .id(o.getId())
                .customerId(o.getCustomerId())
                .currency(o.getCurrency())
                .status(o.getStatus())
                .totalAmount(o.getTotalAmount())
                .build();
    }

    @Test
    void getOrdersWithoutStatusFetchesAllSortedDesc() {
        Order o1 = order(OrderStatus.CONFIRMED, new BigDecimal("1999.00"));
        Order o2 = order(OrderStatus.PENDING, new BigDecimal("899.00"));
        when(orderRepository.findAll(any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(o1, o2)));
        when(mapper.toOutput(o1)).thenReturn(outputFor(o1));
        when(mapper.toOutput(o2)).thenReturn(outputFor(o2));

        OrderPageOutput result = orderService.getOrders(null, 0, 20);

        assertThat(result.getTotalCount()).isEqualTo(2);
        assertThat(result.getOrders()).hasSize(2);
        verify(orderRepository).findAll(any(PageRequest.class));
    }

    @Test
    void getOrdersWithStatusFetchesOnlyThatStatus() {
        Order o1 = order(OrderStatus.CONFIRMED, new BigDecimal("1999.00"));
        when(orderRepository.findByStatus(eq(OrderStatus.CONFIRMED), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(o1)));
        when(mapper.toOutput(o1)).thenReturn(outputFor(o1));

        OrderPageOutput result = orderService.getOrders(OrderStatus.CONFIRMED, 2, 10);

        assertThat(result.getTotalCount()).isEqualTo(1);
        assertThat(result.getOrders()).hasSize(1);
        verify(orderRepository).findByStatus(eq(OrderStatus.CONFIRMED), any(PageRequest.class));
        assertThat(result.getOffset()).isEqualTo(2);
        assertThat(result.getLimit()).isEqualTo(10);
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
}