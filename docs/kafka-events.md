# Kafka Events (Phase 5) — Hybrid Event-Driven + Saga Compensation

The platform is **event-driven** around the order/payment lifecycle, but keeps a
**synchronous cold path** for the one RPC that must answer in-line: the gRPC
`Charge`. Everything else travels as **facts** on Kafka. The result is a
choreographed saga that demonstrates the classic patterns a Staff engineer must
be able to reason about out loud: transactional outbox, Avro + Schema Registry,
at-least-once + idempotent consumers, DLQ + replay, and compensation.

Terminology used here: an **event** is always a *past-tense fact* (`OrderCreated`,
`PaymentRefunded`), never a command.

---

## 1. Topology

```
                    warm path (synchronous, request-scoped)
              ┌──────────────────────────────────────────────┐
              │  createOrder → order-service → gRPC Charge   │
              │              → payment-service :50051         │
              └──────────────────────────────────────────────┘

                       facts (async, durable, fan-out)
  order-service                Kafka (KRaft, 3ⅹ partitions)          payment-service
 ┌────────────────┐   ┌──────────────────────────────────┐   ┌────────────────────┐
 │ JPA tx         │   │                                  │   │ SAGA (async side)  │
 │ ▸ orders       │   │  orders.created        ──────────┼──▶│                    │
 │ ▸ outbox(row)  │──▶│  orders.cancelled      ────comp.─┼──▶│ refund/void        │
 │ relay job      │   │  payments.succeeded    ◀──────────┼─── common consumer    │
 │ (SKIP LOCKED)  │   │  payments.failed       ◀──────────┼─── (orders.created)   │
 │ ▸ publishes    │   │  payments.voided       ◀──────────┼─── publisher          │
 │ ▸ deletes      │   │  payments.refunded     ◀──────────┼───                    │
 └────────────────┘   │  (all Avro, SR-managed)           │   └────────────────────┘
                      └──────────────────────────────────┘
```

**Why hybrid?** A fully-async `createOrder` (poll until paid) makes the UI and
the `Charge` idempotency machinery harder to reason about, and adds failure
modes with little benefit for a single settlement. Instead:

- The **sync gRPC `Charge`** (Resilience4j retry ×3 + circuit breaker,
  persisted idempotency keys, `UPDATE … WHERE status='PENDING'` guard) is the
  authoritative settlement. It already gives exactly-once money movement.
- **Kafka carries the facts** around that settlement. It is *not* the primary
  consistency mechanism for charging — it is the async consequence layer, the
  compensating trigger, and the audit/reconciliation record.

The one thing that *must* be asynchronous because it happens *after* a human
decision with no request in flight — **order cancellation** — is implemented as
a choreographed saga:

```
OrderCancelled (order outbox) ──▶ payment-service refunds captured payment ──▶ PaymentRefunded
                                                                                    │
                                                     order-service consumes ◀───────┘
                                                     guarded UPDATE → REFUNDED
```

---

## 2. Events, Topics, Contracts

All events live in `common/events/avro/*.avsc` (namespace `com.commerce.events`).

| Topic (3 partitions) | Event | Key | Producer | Consumers |
|---|---|---|---|---|
| `orders.created` | `OrderCreated` | `orderId` | order-service (outbox) | payment-service |
| `orders.cancelled` | `OrderCancelled` | `orderId` | order-service (outbox) | payment-service (compensation) |
| `payments.succeeded` | `PaymentSucceeded` | `orderId` | payment-service | order-service |
| `payments.failed` | `PaymentFailed` | `orderId` | payment-service (reserved) | order-service |
| `payments.voided` | `PaymentVoided` | `orderId` | payment-service | order-service |
| `payments.refunded` | `PaymentRefunded` | `orderId` | payment-service | order-service |

**Every event is keyed by `orderId`** → consistent hashing pins all events of an
order to one partition, so per-order ordering is preserved without cross-partition
joins. Each event carries `occurredAt` (RFC3339 UTC) and `correlationId`; money is
always `long` **minor units** (paisa/cents — never floats), matching the gRPC
contract.

Schemas are registered at `common/events/avro` and versioned by the registry.
The **Schema Registry is the single source of truth** for the wire contract:
- **JVM side** (order-service) compiles the `.avsc` via `avro-maven-plugin`
  (`build/avro-maven/avro` configuration targeting `../common/events/avro`) and
  `auto.register.schemas=false` — schemas must exist before the service boots.
- **Go side** (payment-service) resolves schemas from the registry at runtime
  (subject latest for produce, by-ID for consume) — it never embeds copies.

### Schema Registry
- Service: `schema-registry` (CP 7.6), host port `8089`, subjects `<topic>-value`
  (per-topic-value default naming strategy).
- Compatibility: **FULL** set per subject (`scripts/register-schemas.sh`) — a new
  field must have a default, deletions/additions that break existing readers fail
  at *registration* time (good: contract drift surfaces at the producer, not in
  the consumer).
- Browse: Kafka UI → Schema Registry, or `curl localhost:8089/subjects`.

---

## 3. Reliability Building Blocks

### 3.1 Transactional outbox (order-service — writes)
`persistPendingOrder` and `cancelOrder` write the business row **and** an
`outbox` row (`aggregate_id`, `event_type` `OrderCreated`/`OrderCancelled`,
`payload` JSON) in the **same JPA transaction** — so "order is CANCELLED" and
"`OrderCancelled` is pending" are atomic. A `@Scheduled` relay claims a batch
with

```sql
SELECT * FROM outbox WHERE id IN (
  SELECT id FROM outbox ORDER BY created_at LIMIT :batch
  FOR UPDATE SKIP LOCKED
)
```

`SKIP LOCKED` makes the relay **multi-instance-safe**: N replicas poll the same
table and never double-send. The relay publishes (Avro, `acks=all`,
idempotence on) then deletes the row — **at-least-once**: a crash between publish
and delete re-sends, which is why consumers must be idempotent.

### 3.2 Best-effort publish (payment-service — writes)
Payment events are published **after commit**, best-effort. Rationale: the
settlement itself executes synchronously and is already exactly-once; the event
is a *consequence*, not a dependency, of the settlement. If a `PaymentSucceeded`
publish fails the charge is still correct (the order learns via the sync RPC).
Documented asymmetry — order-side writes use the outbox (transactionally strong),
payment-side writes are at-least-once without an outbox.

### 3.3 Idempotent consumers + set-once guards (order-service — reads)
Every `handlePayment*` in `OrderService` runs a **guarded transition**

```sql
UPDATE orders SET status='CONFIRMED', payment_id=? WHERE id=? AND status='PENDING'
```

`@Modifying` with a `WHERE status=…` predicate means a duplicate `PaymentSucceeded`
(replay, redelivery) updates 0 rows instead of corrupting state. Consumers therefore
don't need a dedupe table — the state machine is the guard.

### 3.4 Compensation (payment-service — reads `orders.cancelled`)
Group `payment-compensation` decodes `OrderCancelled` and, by payment state:

| Payment state | Action | Published |
|---|---|---|
| `CAPTURED` / `PARTIALLY_REFUNDED` | `Refund(…, idempotencyKey="cancel:<orderId>")` full amount | `PaymentRefunded` |
| `AUTHORIZED` | `Void(…)` | `PaymentVoided` |
| `REFUNDED` / `VOIDED` / `FAILED` / `PENDING` | no-op (idempotent) | — |
| no payment | no-op | — |

Idempotency is doubly guarded: the refund runs under its own key
(`cancel:<orderId>` dedupes in the DB), and the order-side consumer uses the
`REFUNDED` set-once transition. A duplicated `OrderCancelled` therefore settles
to a no-op on both sides.

### 3.5 DLQ + replay
Both consumers pin a `DefaultErrorHandler` (order) / retry+park logic (payment)
so that a **poison record or persistent business error never blocks the
partition**:

| Consumer group | DLQ topic | Policy |
|---|---|---|
| `order-saga` | `<topic>.order-saga.DLQ` | `ErrorHandlingDeserializer` + `FixedBackOff(1s, 3)`; corrupt bytes (undecodable) also go to DLQ; well-formed payloads that fail the listener are retried 3× then DLQ'd |
| `payment-compensation` | `orders.cancelled.payment-compensation.DLQ` | handle → retry 3× (250ms backoff) → park on DLQ with `original-topic` header |

Replay story (do this only after fixing schema/logic or dropping into consumers
that now ignore dupes synchronously): republish DLQ records back to the source
topic from Kafka UI's consumer search, or `kcat -t <dlq> -P -t <topic>`. The
guarded transitions make replay safe by construction.

---

## 4. Correlation IDs Over the Async Boundary

The request-scoped `X-Correlation-Id` (order) / `correlation-id` metadata
(payment, via gRPC) cannot cross Kafka implicitly. The rule:

> **The correlation ID travels inside the Kafka record** — as the `correlationId`
> header *and* the Avro field. Consumers restore it into the MDC for the duration
> of the handler so their structured logs stay joinable to the originating saga.

- **order → payment**: `CorrelationInterceptor` puts the ID on gRPC outbound
  metadata; `UnaryLogging` (payment) reads it, injects it into `ctx`, and each
  `Payment*` fact embeds it.
- **payment → order**: `PaymentEventConsumer` reads the header in `@Header`,
  pushes it into `MDC` (`key=correlationId`) around the listener invocation.
- **order → payment (compensation)**: outbox relay copies the ID captured at
  cancel time into the Avro `correlationId` field + header; the Go consumer
  reads it back into `ctx` before refunding.

Log drivers: `pattern` conversion on order-service renders `%X{correlationId}`;
payment-service (`slog`) logs it as an attribute. This is what makes the saga
traceable in Loki (search `{svc=~"order-service|payment-service"} |= "cid-…"`).

---

## 5. Observability

- **Kafka UI** (`http://localhost:8086`, provectus/kafka-ui): topics, consumer
  groups + lag, messages (Avro-decoded via the SR connector), Schema Registry.
- **Grafana** `Commerce — Metrics` dashboard → new row **"Saga — Kafka events"**:
  - *Consumer lag (records pending)*: `kafka_consumer_fetch_manager_records_lag`
    (Micrometer auto-exports Spring-Kafka client metrics — zero code).
  - *Consumer records consumed (rate)*: `rate(kafka_consumer_fetch_manager_records_consumed_total…)`.
- **Kafka** runs **KRaft** (no ZooKeeper): `KAFKA_PROCESS_ROLES=controller,broker`,
  controller quorum `1@kafka:29093`, single node — clean operator story, and the
  compose file documents the `KRAFT` K/V store volume.

---

## 6. Running It

```bash
# 0. start the stack (order/payment now depends_on kafka healthy + schema-registry)
./scripts/run-demo.sh docker

# 1. create topics (3 partitions each) + register schemas with FULL compatibility
./scripts/register-schemas.sh

# 2. watch the whole saga (create → CONFIRMED → cancel → REFUNDED) + group lag
./scripts/saga-demo.sh

# 3. browse
open http://localhost:8086   # Kafka UI
open http://localhost:3001   # Grafana → Commerce — Metrics → Saga — Kafka events
```

---

## 7. Code Map

| Concern | Location |
|---|---|
| Schemas (source of truth) | `common/events/avro/*.avsc`, `common/events/README.md` |
| Topic/schema registration | `scripts/register-schemas.sh` |
| Saga driver | `scripts/saga-demo.sh` |
| Order outbox (write side) | order-service `entity/OutboxEvent`, `repository/OutboxRepository` (SKIP LOCKED), `event/OrderEventOutbox`, `job/OutboxRelayJob` |
| Order Kafka wiring | `config/KafkaConfig` (factories, DLQ handler), `event/PaymentEventConsumer` (MDC restore) |
| Payment publish (facts) | payment-service `internal/event/{publisher,schema}.go`, service options |
| Payment compensation (read side) | `internal/event/cancellation.go` (+DLQ), `service.HandleOrderCancelled` |
| Pain points / trade-offs | outbox vs best-effort publish asymmetry (§3.1–3.2), at-least-once + guards (§3.3), replay (§3.5) |