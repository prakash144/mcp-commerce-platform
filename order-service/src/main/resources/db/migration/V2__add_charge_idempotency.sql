ALTER TABLE orders
    ADD COLUMN idempotency_key VARCHAR(64),
    ADD COLUMN payment_id VARCHAR(64),
    ADD COLUMN charge_attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN last_charge_error VARCHAR(255),
    ADD COLUMN next_retry_at TIMESTAMPTZ;

CREATE UNIQUE INDEX idx_orders_idempotency_key ON orders(idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX idx_orders_status_next_retry_at ON orders(status, next_retry_at);