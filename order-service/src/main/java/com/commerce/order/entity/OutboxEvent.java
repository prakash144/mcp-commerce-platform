package com.commerce.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;

import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per saga fact that still needs to be published to Kafka. Written in the
 * same DB transaction as the order it describes (transactional outbox), so an
 * event is durable the moment the business state is. The relay publishes the row
 * and deletes it; a crash in between only re-publishes (at-least-once), which
 * consumers already expect and dedupe.
 */
@Entity
@Table(name = "outbox")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxEvent {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "aggregate_type", nullable = false, length = 32)
    @Builder.Default
    private String aggregateType = "order";

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    // Stored as jsonb (structured, queryable); the JdbcTypeCode hint makes
    // Hibernate bind the String with a ::jsonb cast instead of a varchar.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}