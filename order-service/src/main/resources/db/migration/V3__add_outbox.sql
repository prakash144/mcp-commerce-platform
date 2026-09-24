-- Phase 5: transactional outbox for saga events (OrderCreated / OrderCancelled).
-- The order row and its outbox row are written in the SAME database transaction,
-- so the event is never lost if the process dies between "order saved" and
-- "kafka published". The relay publishes then deletes the row; a crash between
-- publish and delete re-publishes (at-least-once) — consumers are idempotent.
CREATE TABLE outbox (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_id   UUID        NOT NULL,            -- orderId (Kafka record key)
    aggregate_type VARCHAR(32) NOT NULL DEFAULT 'order',
    event_type     VARCHAR(64) NOT NULL,            -- e.g. OrderCreated, OrderCancelled
    payload        JSONB       NOT NULL,            -- Avro record serialized to JSON
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_outbox_created_at ON outbox(created_at);

-- Order lifecycle gains a terminal REFUNDED state (persisted as VARCHAR status),
-- reached when the choreographed compensation completes:
--   OrderCancelled -> payment-service refund -> PaymentRefunded -> REFUNDED
-- No schema change needed for the enum value itself.