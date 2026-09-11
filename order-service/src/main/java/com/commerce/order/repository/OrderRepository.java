package com.commerce.order.repository;

import com.commerce.order.entity.Order;
import com.commerce.order.entity.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {
    Page<Order> findByCustomerId(String customerId, Pageable pageable);

    Page<Order> findByStatus(OrderStatus status, Pageable pageable);

    @Query("select count(o) from Order o")
    long countOrders();

    @Query("select coalesce(sum(o.totalAmount), 0) from Order o where o.status = :status")
    BigDecimal sumTotalByStatus(@Param("status") OrderStatus status);
}