# Staff Interview Blueprint — Commerce Platform

Study guide that maps **everything built in this repo** to the questions a Staff /
principal engineering panel actually asks. Use it to rehearse answers out loud;
every claim here is backed by code you wrote in `product-service`,
`order-service`, `payment-service`, `web/`, and `common/events`.

**How to use:** pick one pillar → describe the system (2 min), explain the hardest
trade-off (2 min), then walk the code path (file:line). Do it out loud; the panel
hears your *reasoning*, not your memory.

---

## Pillar 1 — The architecture conversation ("walk me through the system")

**Lay it out in layers:** four services, each with one protocol (REST / GraphQL /
gRPC / MCP) + one owned datastore, behind a gateway, over Kafka; shared contracts
in `common/`. Reference `docs/plan.md` §2 + `docs/infrastructure.md`.

**Why one-protocol-per-service?** Each service *is* a tutorial for the protocol it
owns; the surface is small. Trade-off: coordinating four protocols is real, but
each boundary is a *textbook* (see Pillar 5's cross-cutting answers). Repeated
"REST everywhere" would teach nothing and still need the same coordination.

**The interesting decision** to volunteer: **hybrid sync/async** (Phase 5). The
money-movement RPC stays synchronous because it must answer in-line and already
has exactly-once idempotency; Kafka carries **facts** + the one genuinely async
flow (cancellation → refund saga). *Probing question it invites:* "why not
everything on Kafka?" — answer with risk analysis for the full-async design
(polling UX, ordering between sync reply and async event, two sources of truth
for 'paid').

**Story mineral:** "we chose the outbox not for hype but because one cancellation
during a Kafka restart proved the poll-then-publish gap loses money." Use
`docs/kafka-events.md` §3.

---

## Pillar 2 — Concurrency and money safety ("how do you not lose money?")

Own this cold. It is the highest-signal cluster.

1. **Idempotency keys everywhere:** `createOrder` accepts `idempotencyKey`;
   `Charge` requires one; `Refund` requires one; the *replay* returns the original
   result without re-executing (`service.FindByIdempotencyKey` fast path).
2. **Exactly-once settlement guard:** payment transitions via
   `UPDATE … WHERE status='PENDING'` (order) / status checks in the gRPC state
   machine (payment) — a retried request can set `CAPTURED` exactly once.
3. **Money as `int64` minor units**, never floats — matches gRPC proto and the
   Avro `long`; typo-prone nowhere, exact by construction (₹ paise / cents).
4. **Transactional outbox** is the "tell me about eventual consistency" moment:
   order + event commit atomically; `SKIP LOCKED` relay makes it multi-instance
   safe; at-least-once means *consumers must be idempotent* — which is why every
   state transition is a guarded `UPDATE`.

**Practice Qs:** *"Two `cancelOrder` calls arrive at once"* → dedupe key on
refund + set-once `REFUNDED` transition ⇒ no double money movement. *"Relay
crashes between publish and delete"* → duplicate `OrderCreated`; consumer guard
makes it a no-op. *"Kafka is down when the order is created"* → outbox keeps the
row; relay catches up on return. All three are `kafka-events.md` §3 + tests.

**Anti-trap:** don't claim "exactly once" from Kafka. Say: *at-most-once
production (outbox) + at-least-once delivery + idempotent consumption = end-to-end
exactly-once effect.*

---

## Pillar 3 — Reliability & resilience ("what happens when X fails?")

Resilience4j stack on the `Charge` path (order → payment): **retry ×3**,
**circuit breaker**, **bulkhead**; failure marks the order `PENDING` and a
`@Scheduled` job with backoff + attempt cap (`charge.attempts`, `next_retry_at`)
re-promotes until it settles `CONFIRMED` or dies `FAILED`. Tests prove:
`PendingOrderRetryJobTest`, `GrpcPaymentClientTest`, `ResilienceConfigTest`.

**Deliberate failure-mode table (recite!):**

| Failure | Observable behavior | Why it's correct |
|---|---|---|
| payment-service down on charge | order PENDING, UI retries later, no hang | retries + async job, not a blocking timeout |
| payment-service down on cancel | OrderCancelled safely in outbox | compensation runs when it recovers |
| poison Kafka record | lands in `<topic>.order-saga.DLQ` | partition never blocks |
| duplicated event | guarded UPDATE updates 0 rows | idempotent state machine |
| consumer restarts | rejoin, redeliver from last ack | RECORD ack mode, at-least-once |

**Story mineral:** the DLQ exists because "one bad record blocks a partition for
everyone." Grep `KafkaConfig#DefaultErrorHandler`.

---

## Pillar 4 — Observability ("can you debug production?")

- **Correlation IDs across the async boundary** — the subtle part: a header
  doesn't propagate across Kafka. Rule you built: the ID lives *in* the record
  (header + Avro field) and is restored to the MDC per consumer call
  (`PaymentEventConsumer.withCorrelation`, Go `WithCorrelation`). This is the
  "async boundary breaks request-scoped state" lesson — say it explicitly.
- **Metrics** — RED for HTTP (`http_server_requests_seconds_*`), gRPC
  (`grpc_server_handling_seconds_*`), USE for JVM/Go runtime, business counters
  (`commerce_orders_total`, `commerce_revenue_minor_total`,
  `commerce_circuitbreaker_state`). New: **Kafka consumer lag** panel
  (Spring-Kafka's Micrometer export; lag ≈ 0 is the dashboard answer to "is the
  saga healthy?").
- **Logs** — `slog` (Go, JSON) + logback (Java) → Loki, `svc` label, `{app=…}`
  search, LogQL cheat-sheet in `docs/infrastructure.md`.

**The panel question:** "how do you know checkout is degrading?" → consumer lag
rising, 5s on order + payment, circuit-breaker half-open. One dashboard, one glance.

---

## Pillar 5 — Cross-cutting systems design ("you said X, now defend it")

- **Event naming** — past-tense facts (`OrderCreated`), never imperatives.
- **Schema Registry** — Avro at `common/events/avro`, `FULL` compatibility so a
  new optional field can't break readers; **JVM compiles schemas at build time,
  Go resolves from the registry at runtime** — explain why the divergence exists
  (compile-time type safety vs dynamic), and that SR is the source of truth.
- **Key by `orderId`** — consistent hashing ⇒ per-order ordering on one
  partition, no cross-partition joins for a single saga. Give the counter-case:
  high-cardinality hot keys need key salting; here a partition-per-order is fine
  at this volume.
- **DLQ + replay** — poison isolation (pollution containment) vs. replay
  (recovery). Replay is safe *because* consumers are guarded updates.
- **Gateway/protocol boundaries** — Kong on the edge (Planned), client ↔ services
  over REST/GraphQL, services ↔ services over gRPC, facts over Kafka. MCP tools as
  adapters only (planned) — "tools never touch the DB, they call the APIs."

---

## Pillar 6 — The three story buckets (STAR, each ≤90s, code-backed)

1. **"Tell me about a time you designed for failure."** → Kafka outbox + DLQ +
   retry job. Concrete bug avoided: poll-then-publish gap losing an event during
   restart; fix = transaction + relay (`kafka-events.md` §3.1, `V3__add_outbox.sql`).
2. **"Tell me about a time you fixed correctness."** → idempotent
   refund-on-cancel: same `OrderCancelled` delivered twice created one refund,
   not two (`refund` idempotency key `cancel:<orderId>` + `REFUNDED` guard; covered
   by `TestHandleOrderCancelledReplayIsIdempotent`).
3. **"Tell me about a time you made an async system debuggable."** → correlation
   ID through the async boundary + Kafka lag dashboard; log lines joinable from
   `cid-…` in Loki.

---

## Whiteboard / systems-design drill (20-min loop)

Design "cancel an order and recover the money" on the board:

1. Write the state machines (order: `PENDING→CONFIRMED→… ; CANCELLED→REFUNDED`;
   payment: `AUTHORIZED/CAPTURED→VOIDED/REFUNDED`).
2. Pick the consistency approach → choreographed saga over Kafka; draw the arrows
   (`OrderCancelled → refund → PaymentRefunded`).
3. Then **attack it**: duplicate cancel? → set-once. `Cancel` during
   `PENDING`/retry window? → guarded transitions + terminal-state checks. Kafka
   down during compensation? → outbox + DLQ + replay. Idempotency key for refund?
   → `cancel:<orderId>`.
4. Close with the trade-off you'd defend: **why not orchestrator (Temporal/Saga
   engine)?** → choreography keeps a two-service system decoupled without
   introducing an orchestrator to operate; trade-off is no global view of saga
   state — mitigated by Kafka UI + lag dashboard + camelCase facts for audit.

---

## Quick-fire facts (memorize; each maps to a test/source)

- Idempotency is enforced at **two** layers on refunds: DB key + state guard.
- Order statuses: `PENDING/CONFIRMED/CANCELLED/FAILED/REFUNDED`; REFUNDED is the
  terminal saga outcome, reached only after `PaymentRefunded`.
- Payment statuses: `AUTHORIZED/CAPTURED/PARTIALLY_REFUNDED/REFUNDED/VOIDED/FAILED`.
- Outbox claim uses `FOR UPDATE SKIP LOCKED` — safe across replicas.
- Kafka runs **KRaft** (no ZooKeeper) — `KAFKA_PROCESS_ROLES=controller,broker`.
- `order-saga` group = 4 listeners (succeeded/failed/voided/refunded);
  `payment-compensation` group = 1 (orders.cancelled).
- Money minor units everywhere: gRPC `int64`, Avro `long`, DB `BIGINT`.
- Every event keyed by `orderId`; 3 partitions per topic; `FULL` compat per subject.
- Demo tools: `scripts/run-demo.sh`, `scripts/register-schemas.sh`,
  `scripts/saga-demo.sh`, Kafka UI `:8086`, Grafana `:3001`.

---

## 30-second pitch (open the panel with this)

> Four protocols, one per service, each a textbook: REST with RFC-7807 errors,
> GraphQL with DataLoader and typed `extensions.code` errors, gRPC with interceptors
> and health checks, and a choreographed Kafka saga for the async lifecycle. The
> design center is money safety — idempotency keys, guarded state transitions, and a
> transactional outbox — so asynchronous consistency never risks double-charging or
> losing an event. Every failure has an observable, test-backed outcome.