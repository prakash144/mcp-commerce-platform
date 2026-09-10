# Payment Service

**Protocol:** gRPC (Go 1.26)
**Responsibility:** Payment lifecycle — charge, capture, void, refund, status

---

## Architecture (Layers)

```
┌─────────────────────────────────────────────────────────────────────┐
│  order-service (Java, GraphQL)                                       │
│  gRPC client with deadline + retry (internal only, never internet ──┼──▶
└──────────────────────────────────┬──────────────────────────────────┘
                                   │
                                   ▼
┌─────────────────────────────────────────────────────────────────────┐
│  PaymentHandler                     (thin gRPC adapter)             │
│  - implements paymentv1.PaymentServiceServer                        │
│  - maps service errors -> gRPC codes (NotFound/InvalidArgument/     │
│    FailedPrecondition)                                              │
│  - maps model <-> proto (enums, timestamps)                         │
└──────────────────────────────────┬──────────────────────────────────┘
                                   │
                                   ▼
┌─────────────────────────────────────────────────────────────────────┐
│  paymentService                      (business logic — no gRPC)     │
│  - Charge: authorize+capture, idempotent via idempotency_key        │
│  - Capture: AUTHORIZED -> CAPTURED                                  │
│  - Void:    AUTHORIZED -> VOIDED                                    │
│  - Refund:  CAPTURED -> REFUNDED / PARTIALLY_REFUNDED               │
│  - state machine guards every transition                            │
└──────────────────────────────────┬──────────────────────────────────┘
                                   │
                                   ▼
┌─────────────────────────────────────────────────────────────────────┐
│  PaymentRepository                  (GORM)                           │
│  - CreatePayment / FindByID / FindByIDempotencyKey / UpdatePayment  │
│  - CreateRefund / FindRefundByIdempotencyKey / TotalRefundedAmount  │
│  - returns ErrNotFound (repo owns persistence errors)               │
└──────────────────────────────────┬──────────────────────────────────┘
                                   │
                                   ▼
┌─────────────────────────────────────────────────────────────────────┐
│  PostgreSQL (paymentdb)         database-per-service                 │
└─────────────────────────────────────────────────────────────────────┘

    ┌──────────────────┐   ┌──────────────────┐   ┌──────────────────┐
    │  Middleware      │   │  payment.proto   │   │  Config (env)    │
    │  - unary logging │──▶│  (source of      │──▶│  12-factor       │
    │  - panic recovery│   │   truth)         │   │  GRPC_PORT, DB_* │
    └──────────────────┘   └──────────────────┘   └──────────────────┘
```

---

## Build Order

Each layer is built bottom-up (contract first, HTTP/gRPC last).

| # | Layer | File | What it does |
|---|---|---|---|
| 1 | Contract | `proto/payment.proto` | `payment.v1`, 5 RPCs, enums — the single source of truth |
| 2 | Generated stubs | `pkg/generated/` | protoc output (`payment.pb.go`, `payment_grpc.pb.go`) |
| 3 | Model | `internal/model/payment.go` | GORM entities: `Payment`, `Refund` |
| 4 | Repository | `internal/repository/payment_repository.go` | GORM access, sentinel `ErrNotFound` |
| 5 | Service | `internal/service/payment_service.go` | State machine, validation, idempotency |
| 6 | Unit tests | `internal/service/payment_service_test.go` | Fake repo, 75.6% coverage |
| 7 | Handler | `internal/handler/payment_handler.go` | Thin gRPC adapter, error/type mapping |
| 8 | Middleware | `internal/middleware/interceptors.go` | Logging + panic recovery interceptors |
| 9 | Config | `internal/config/config.go` | Env-driven 12-factor config |
| 10 | Server | `internal/server/server.go` | gRPC bootstrap, health check, reflection |
| 11 | Entrypoint | `cmd/server/main.go` | Composition root: DB → repo → svc → handler → server |
| 12 | Verify | `grpcurl` + psql | Boot, charge, replay, refund, inspect DB |

---

## RPCs

| RPC | Description | Legal states → resulting state |
|---|---|---|
| `Charge` | Authorize + capture in one step (order-service checkout) | → `CAPTURED` |
| `Capture` | Finalize an authorization | `AUTHORIZED` → `CAPTURED` |
| `Void` | Cancel an authorization | `AUTHORIZED` → `VOIDED` |
| `Refund` | Full (`amountMinor=0`) or partial money back | `CAPTURED`/`PARTIALLY_REFUNDED` → `REFUNDED`/`PARTIALLY_REFUNDED` |
| `GetPayment` | Status lookup (saga + MCP consumers) | any |

### State Machine

```
Charge ────────────────▶ CAPTURED ──Refund(full)──▶ REFUNDED
                           │  │                          ▲
                       Refund│  └──Refund(partial)──▶ PARTIALLY_REFUNDED
                           │                              │
Capture ──▶ CAPTURED       ▼                              └──Refund
Void ─────▶ VOIDED     PENDING            FAILED (reserved for real gateway)
```

---

## gRPC Error Mapping

| Service error | gRPC code |
|---|---|
| `ErrPaymentNotFound` | `NotFound` |
| `ErrMissingKey`, `ErrInvalidAmount`, `ErrInvalidCurrency` | `InvalidArgument` |
| `ErrIllegalState` | `FailedPrecondition` |
| anything else | `Internal` (logged) |

---

## Key Design Decisions

- **Money is `int64` minor units + ISO 4217 currency** — never floats. `$49.99` → `amountMinor: 4999`.
- **Idempotency keys on all money movement** (`Charge`, `Refund`). A replay returns the original result. Correctness comes from the **unique index** on `idempotency_key`, not just the service-layer check (wins races under concurrency).
- **Contract first** — `payment.proto` is the source of truth; generated stubs are checked in. Service layer never imports protobuf.
- **Repo owns persistence errors** — returns sentinel `ErrNotFound` instead of leaking GORM to the service.
- **gRPC Health Checking Protocol** + **server reflection** — `grpcurl` discovery, orchestrator probes.
- **Interceptors, not inline middleware** — logging and panic recovery are chain decorators, method/code/duration per call.
- **GORM for dev speed** (JPA familiarity); schema is visible via GORM's table DDL at startup. Versioned SQL migrations are a Phase 9 upgrade.
- **Graceful shutdown** — SIGINT/SIGTERM drains in-flight RPCs before exit.

---

## Running & Testing

### Prerequisites

PostgreSQL must be running (databases `productdb,orderdb,paymentdb,...`). From the project root:

```bash
docker compose -f docker/docker-compose.yml up -d postgres
# wait for:  docker compose -f docker/docker-compose.yml ps postgres  → healthy
```

### Boot the service

```bash
cd payment-service
go run ./cmd/server
```

Server listens on `:50051` (set `GRPC_PORT` to override). First boot runs GORM AutoMigrate creating `payments` + `refunds`.

### Install grpcurl

```bash
brew install grpcurl
```

### Test RPCs

**Health check:**
```bash
grpcurl -plaintext localhost:50051 grpc.health.v1.Health/Check
```

**List services (reflection):**
```bash
grpcurl -plaintext localhost:50051 list
```

**Charge (authorize + capture):**
```bash
grpcurl -plaintext \
  -d '{"idempotencyKey":"ord-100","orderId":"3f460937-38f1-4f4b-9f5e-6d5c1f3a2b01","customerId":"a2d5b3f4-9c8e-4b6d-a123-000000000001","amountMinor":4999,"currency":"USD","method":"PAYMENT_METHOD_CARD"}' \
  localhost:50051 payment.v1.PaymentService/Charge
```

**Idempotency replay — resend the same command; you get the same payment back, no new row.**

**Get payment:**
```bash
grpcurl -plaintext -d '{"id":"<paymentId from Charge>"}' \
  localhost:50051 payment.v1.PaymentService/GetPayment
```

**Full refund:**
```bash
grpcurl -plaintext -d '{"paymentId":"<paymentId>","idempotencyKey":"ref-100","amountMinor":0,"reason":"returned"}' \
  localhost:50051 payment.v1.PaymentService/Refund
```

**Capture / Void** require an `AUTHORIZED` payment. `Charge` goes straight to `CAPTURED`, so seed one in the DB to exercise these:
```sql
INSERT INTO payments (id, order_id, customer_id, amount_minor, currency, status, method, idempotency_key, created_at, updated_at)
VALUES ('aaaaaaaa-0000-0000-0000-000000000001', '3f460937-38f1-4f4b-9f5e-6d5c1f3a2b01', 'a2d5b3f4-9c8e-4b6d-a123-000000000001', 20000, 'USD', 'AUTHORIZED', 'CARD', 'seed-1', NOW(), NOW());
```
```bash
grpcurl -plaintext -d '{"paymentId":"aaaaaaaa-0000-0000-0000-000000000001","amountMinor":20000}' \
  localhost:50051 payment.v1.PaymentService/Capture
```

### Inspect tables (IntelliJ Database or psql)

Connection (same as product-service): host `localhost`, port `5432`, db `paymentdb`, user `commerce`, pass `commerce_pass`.

```sql
SELECT id, status, amount_minor, currency, idempotency_key FROM payments;
SELECT id, payment_id, amount_minor, idempotency_key FROM refunds;
```

---

## TODO

- [ ] Integration tests: Testcontainers or sqlmock (repository + handler)
- [ ] Push coverage ≥80% (currently 75.6% on service layer)
- [ ] `ctx` propagation through repository (deadline/cancellation, Phase 6)
- [ ] Auth interceptor (validate gateway-injected headers / mTLS)
- [ ] Kafka events: `PaymentSucceeded`, `PaymentFailed` (Phase 5)
- [ ] Event schema in `common/proto`/`common/events` + Schema Registry
- [ ] Multi-stage Dockerfile + docker compose service
- [ ] Outbox pattern for refund dual-write (stretch)