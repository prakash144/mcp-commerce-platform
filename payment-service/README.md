# Payment Service

**Protocol:** gRPC (Go 1.26) + REST gateway (grpc-gateway)
**Responsibility:** Payment lifecycle — charge, capture, void, refund, status

---

## What is this? (start here if you're new)

This service is one of three microservices in the commerce platform:

| Service | Protocol | Why |
|---|---|---|
| product-service | REST | simple resource CRUD |
| order-service | GraphQL | flexible nested queries |
| **payment-service (this)** | **gRPC** | **internal, low-latency, strongly-typed money movement** |

Three key ideas to understand about payments before touching the code:

1. **gRPC is internal plumbing, not a customer API.** `order-service` calls us
   directly over gRPC on a private network. We are never exposed to the internet
   (Kong gateway handles all external traffic). To make the service debuggable
   and Swagger-testable, we also expose the *same* methods as a REST gateway via
   [grpc-gateway](https://github.com/grpc-ecosystem/grpc-gateway) — the REST
   endpoints are generated from the same `.proto`, so the contract can't drift.

2. **Money is an integer, never a float.** `$49.99` is stored as `amountMinor: 4999`
   + `currency: "USD"`. Floating-point money silently corrupts totals; this
   convention (minor units + ISO 4217) is the industry standard.

3. **Money movement must be idempotent.** If `order-service` retries a `Charge`
   (network glitch, timeout), we must NOT charge the customer twice. Every
   `Charge`/`Refund` carries an `idempotencyKey`; a replay of the same key returns
   the *original* result. The Postgres `unqiue index` on `idempotency_key` is what
   truly enforces this, even if two requests race at exactly the same moment.

Read `docs/plan.md` §2.3 and §4 for the gRPC folder layout and best practices.

---

## Architecture (Layers)

```
┌─────────────────────────────────────────────────────────────────────┐
│  order-service (Java, GraphQL)     REST gateway / Swagger UI        │
│  gRPC client (internal, private ─┐   :8090 (generated from proto)   │
│  network, never internet-facing) │   /v1/payments*  /docs  ──── any │
└──────────────────────────────────┼──────────────────────────────────┘
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
| 1 | Contract | `proto/payment.proto` | `payment.v1`, 5 RPCs, enums + HTTP annotations — the single source of truth |
| 2 | Generated stubs | `pkg/generated/` | protoc output (`payment.pb.go`, `payment_grpc.pb.go`, `payment.pb.gw.go`) |
| 3 | Vendored protos | `third_party/google/api/` | `annotations.proto` + `http.proto` (needed for the REST gateway annotations) |
| 4 | Model | `internal/model/payment.go` | GORM entities: `Payment`, `Refund` |
| 5 | Repository | `internal/repository/payment_repository.go` | GORM access, sentinel `ErrNotFound` |
| 6 | Service | `internal/service/payment_service.go` | State machine, validation, idempotency |
| 7 | Unit tests | `internal/service/payment_service_test.go` | Fake repo, 78% coverage |
| 8 | Handler | `internal/handler/payment_handler.go` | Thin gRPC adapter, error/type mapping |
| 9 | Middleware | `internal/middleware/interceptors.go` | Logging + panic recovery interceptors |
| 10 | Config | `internal/config/config.go` | Env-driven 12-factor config |
| 11 | Server | `internal/server/server.go` | gRPC bootstrap, health check, reflection |
| 12 | OpenAPI spec | `api/` | `payment.swagger.json` (generated) + `index.html` (Swagger UI) |
| 13 | Entrypoint | `cmd/server/main.go` | Composition root: DB → repo → svc → handler → server + REST gateway |
| 14 | Verify | `grpcurl`, `curl`, Swagger UI, psql | Boot, charge, replay, refund, inspect DB |

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

| Service error | gRPC code | REST (gateway) |
|---|---|---|
| `ErrPaymentNotFound` | `NotFound` | HTTP 404 |
| `ErrInvalidPaymentID` | `InvalidArgument` | HTTP 400 |
| `ErrMissingKey`, `ErrInvalidAmount`, `ErrInvalidCurrency` | `InvalidArgument` | HTTP 400 |
| `ErrIllegalState` | `FailedPrecondition` | HTTP 400 |
| anything else | `Internal` (logged) | HTTP 500 |

---

## REST Endpoints (grpc-gateway, port `8090`)

The `.proto` annotations generate these REST routes from the same contract.
A Swagger UI is served at **`http://localhost:8090/docs`** (spec at `/swagger.json`).

| Method | Path | Maps to gRPC RPC |
|---|---|---|
| POST | `/v1/payments` | `Charge` |
| GET | `/v1/payments/{id}` | `GetPayment` |
| POST | `/v1/payments/{payment_id}/refund` | `Refund` |
| POST | `/v1/payments/{payment_id}/capture` | `Capture` |
| POST | `/v1/payments/{payment_id}/void` | `Void` |

REST request/response bodies use the same field names as gRPC (JSON camelCase,
e.g. `{ "idempotencyKey": "...", "amountMinor": 4999 }`).

---

## Key Design Decisions

- **Money is `int64` minor units + ISO 4217 currency** — never floats. `$49.99` → `amountMinor: 4999`.
- **Idempotency keys on all money movement** (`Charge`, `Refund`). A replay returns the original result. Correctness comes from the **unique index** on `idempotency_key`, not just the service-layer check (wins races under concurrency).
- **Contract first** — `payment.proto` is the source of truth; generated stubs are checked in. Service layer never imports protobuf.
- **Repo owns persistence errors** — returns sentinel `ErrNotFound` instead of leaking GORM to the service.
- **gRPC Health Checking Protocol** + **server reflection** — `grpcurl` discovery, orchestrator probes.
- **Interceptors, not inline middleware** — logging and panic recovery are chain decorators, method/code/duration per call.
- **One contract, two doors** — HTTP annotations in `payment.proto` generate the REST gateway, so gRPC and REST can never disagree; the Swagger UI lets you explore the API without `grpcurl`.
- **GORM for dev speed** (JPA familiarity); schema is visible via GORM's table DDL at startup. Versioned SQL migrations are a Phase 9 upgrade.
- **Graceful shutdown** — SIGINT/SIGTERM drains in-flight RPCs and HTTP requests before exit.

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

Two listeners start:
- gRPC on `:50051` (set `GRPC_PORT` to override)
- REST gateway + Swagger UI on `:8090` (set `REST_PORT` to override)

First boot runs GORM AutoMigrate creating `payments` + `refunds`.

### Quick interactive check — Swagger UI

Open **`http://localhost:8090/docs`** in a browser. You'll see the Swagger UI
with all 5 operations. Click **PaymentsService_Charge → Try it out**, paste a
request body, and execute — no command-line tools needed.

Raw spec for other tools: `http://localhost:8090/swagger.json`.

### Test via REST (curl — works without grpcurl)

```bash
# Charge
curl -X POST http://localhost:8090/v1/payments \
  -H 'Content-Type: application/json' \
  -d '{"idempotencyKey":"ord-100","orderId":"3f460937-38f1-4f4b-9f5e-6d5c1f3a2b01","customerId":"a2d5b3f4-9c8e-4b6d-a123-000000000001","amountMinor":4999,"currency":"USD","method":"PAYMENT_METHOD_CARD"}'

# Read it back
curl http://localhost:8090/v1/payments/<id>

# Refund
curl -X POST http://localhost:8090/v1/payments/<id>/refund \
  -H 'Content-Type: application/json' -d '{"idempotencyKey":"ref-100","amountMinor":0}'
```

> Tip: resend the exact same Charge — you get the same payment back (idempotency).

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
- [ ] Push coverage ≥80% (currently 78% on service layer)
- [ ] `ctx` propagation through repository (deadline/cancellation, Phase 6)
- [ ] Auth interceptor (validate gateway-injected headers / mTLS)
- [ ] Pin `third_party/google/api` protos to a tagged googleapis release (currently vendored from `master`)
- [ ] Kafka events: `PaymentSucceeded`, `PaymentFailed` (Phase 5)
- [ ] Event schema in `common/proto`/`common/events` + Schema Registry
- [ ] Multi-stage Dockerfile + docker compose service
- [ ] Outbox pattern for refund dual-write (stretch)