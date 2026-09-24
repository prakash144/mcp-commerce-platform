package com.commerce.order.repository;

import com.commerce.order.entity.Order;
import com.commerce.order.entity.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {
    Page<Order> findByCustomerId(String customerId, Pageable pageable);

    Page<Order> findByStatus(OrderStatus status, Pageable pageable);

    @Query("""
            select o from Order o
            where o.status = 'PENDING'
              and o.chargeAttempts > 0
              and o.nextRetryAt <= :now
            order by o.nextRetryAt
            """)
    List<Order> findPendingDue(@Param("now") Instant now, Pageable pageable);

    @Query("select count(o) from Order o")
    long countOrders();

    @Query("select coalesce(sum(o.totalAmount), 0) from Order o where o.status = :status")
    BigDecimal sumTotalByStatus(@Param("status") OrderStatus status);

    // ── guarded transitions (exactly-once effect) ─────────────────────────────
    // Each consumer event maps to an UPDATE that only applies from one specific
    // previous state. Replays (at-least-once Kafka) hit a no-op instead of a
    // double-effect, and concurrent consumers can't trample each other: the row
    // is evaluated under an atomic UPDATE ... WHERE status = 'PENDING'.

    @Modifying
    @Query("""
            update Order o
               set o.status = 'CONFIRMED', o.paymentId = :paymentId
             where o.id = :id and o.status = 'PENDING'
            """)
    int confirmIfPending(@Param("id") UUID id, @Param("paymentId") String paymentId);

    @Modifying
    @Query("""
            update Order o
               set o.status = 'FAILED', o.lastChargeError = :reason, o.nextRetryAt = null
             where o.id = :id and o.status = 'PENDING'
            """)
    int failIfPending(@Param("id") UUID id, @Param("reason") String reason);

    @Modifying
    @Query("""
            update Order o
               set o.status = 'REFUNDED'
             where o.id = :id and o.status = 'CANCELLED'
            """)
    int refundIfCancelled(@Param("id") UUID id);
}