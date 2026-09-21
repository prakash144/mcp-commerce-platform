package com.commerce.order.repository;

import com.commerce.order.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Claims the next batch of unpublished events for THIS instance only.
     * {@code FOR UPDATE SKIP LOCKED} means several relay instances can poll
     * concurrently without dispatching the same row twice (the groundwork for
     * spring-profile multi-instance order-service — Phase 5.5/ADR-002).
     * The subquery is required because Postgres rejects FOR UPDATE combined with
     * LIMIT on the same level.
     */
    @Query(value = """
            select * from outbox
            where id in (
                select id from outbox
                order by created_at
                limit :batch for update skip locked
            )
            """, nativeQuery = true)
    List<OutboxEvent> claimBatch(@Param("batch") int batch);
}