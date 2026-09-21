# Event Contracts (`common/events/`)

Avro schemas are the **source of truth** for Kafka facts in this platform — one
`.avsc` per event, registered in Confluent Schema Registry (`:8089` host /
`:8081` internal) with `compatibility=FULL`. Register with:

```bash
./scripts/register-schemas.sh
```

## Topics & subjects

| Topic | Value subject | Event (`.avsc`) | Producer | Consumers |
|---|---|---|---|---|
| `orders.created` | `orders.created-value` | `OrderCreated` | order-service (outbox relay) | payment-service (charge trigger), audit |
| `orders.cancelled` | `orders.cancelled-value` | `OrderCancelled` | order-service (outbox relay) | payment-service (refund/void compensation) |
| `payments.succeeded` | `payments.succeeded-value` | `PaymentSucceeded` | payment-service | order-service (mark PAID) |
| `payments.failed` | `payments.failed-value` | `PaymentFailed` | payment-service | order-service (mark FAILED) |
| `payments.voided` | `payments.voided-value` | `PaymentVoided` | payment-service | order-service (terminal CANCELLED) |
| `payments.refunded` | `payments.refunded-value` | `PaymentRefunded` | payment-service | order-service (mark REFUNDED) |

**Naming strategy:** per-topic-value (default) → exactly one value schema per
topic, no record unions. A new event version must be `FULL`-compatible with the
last (adding a field with a default is fine; renaming/reordering is not).

## Contract rules (the non-negotiables)

1. **Key = `orderId`** on every event → all facts for one order land in the same
   partition, so per-order ordering holds.
2. **Partition count:** 3 per topic (`TOPIC_PARTITIONS` in `register-schemas.sh`).
   Bump before production traffic — changing it later breaks per-key ordering.
3. **Payloads are self-contained facts** (past tense, complete): consumers never
   call back to a source service for data they could have been given.
4. **Delivery is at-least-once**, so every consumer must be **idempotent** (dedupe
   key / set-once transition guard) and ship poison records to a DLQ.
5. Every event carries `correlationId` — consumers restore it into their logging
   MDC so a saga stays traceable end-to-end.
6. Money is `long` **minor units** + `currency` (same rule as the gRPC contract).