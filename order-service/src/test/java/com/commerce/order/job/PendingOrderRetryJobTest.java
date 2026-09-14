package com.commerce.order.job;

import com.commerce.order.entity.Order;
import com.commerce.order.entity.OrderStatus;
import com.commerce.order.repository.OrderRepository;
import com.commerce.order.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PendingOrderRetryJobTest {

    @Mock private OrderRepository orderRepository;
    @Mock private OrderService orderService;

    private PendingOrderRetryJob job;

    @BeforeEach
    void setUp() {
        job = new PendingOrderRetryJob(orderRepository, orderService);
    }

    @Test
    void processesOnlyDuePendingOrders() {
        UUID dueId = UUID.randomUUID();
        UUID notDueId = UUID.randomUUID();
        when(orderRepository.findPendingDue(any(Instant.class), any(PageRequest.class)))
                .thenReturn(List.of(single(dueId, OrderStatus.PENDING)));

        job.retryDueOrders();

        verify(orderService).retryPending(dueId);
        verify(orderService, never()).retryPending(notDueId);
    }

    @Test
    void doesNothingWhenNothingIsDue() {
        when(orderRepository.findPendingDue(any(Instant.class), any(PageRequest.class)))
                .thenReturn(List.of());

        job.retryDueOrders();

        verify(orderService, never()).retryPending(any(UUID.class));
    }

    @Test
    void skipsBrokenOrdersWithoutAbortingTheBatch() {
        UUID brokenId = UUID.randomUUID();
        UUID okId = UUID.randomUUID();
        when(orderRepository.findPendingDue(any(Instant.class), any(PageRequest.class)))
                .thenReturn(List.of(single(brokenId, OrderStatus.PENDING), single(okId, OrderStatus.PENDING)));
        when(orderService.retryPending(brokenId)).thenThrow(new RuntimeException("boom"));

        job.retryDueOrders();

        verify(orderService).retryPending(okId);
    }

    private Order single(UUID id, OrderStatus status) {
        return Order.builder().id(id).status(status).build();
    }
}