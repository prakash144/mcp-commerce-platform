# Development Tracking

Live tracking for the platform project — pending work ([Todos](#todos)) and what
has been built in each session ([Session History](#session-history)). Project
overview: [root README](../README.md).

## Todos

> Legend: 🔴 **Must** complete (gates the working demo) · 🟡 **Should** (production-style) · 🔵 **Nice-to-have**

| Area | Task | Priority | Status |
|---|---|---|---|---|
| **Frontend** | Build React + Vite storefront (`web/`) — catalog, cart, checkout, order status | 🔴 | ✅ |
| **Frontend** | Admin dashboard (`/admin`) — Dashboard KPIs, Products CRUD, Orders view/cancel, Payments view/refund | 🔵 | ✅ |
| **Backend** | Admin APIs — `orders(first,offset,status)` + `orderStats` (GraphQL), `ListPayments` REST (payment) | 🔴 | ✅ |
| **Backend** | Replace order-service `StubPaymentClient` with a real gRPC `Charge` → payment-service `:50051` | 🔴 | ✅ |
| **Events** | Kafka events (Phase 5) — Outbox relay (OrderCreated/Cancelled, PaymentSucceeded/Failed), idempotent consumers, DLQ + replay | 🔴 | ✅ |
| **Edge** | Kong API Gateway — routes for product/order (+ payment REST), JWT plugin, rate limiting | 🟡 | ⬜ |
| **Edge** | Keycloak — realm, web client (PKCE), MCP client-credentials, JWKS wired to Kong | 🟡 | ⬜ |
| **Frontend** | Real search — product-service full-text search endpoint (search is client-side filtered today) | 🟡 | ⬜ |
| **Frontend** | Keycloak sign-in (PKCE) replacing the mock identity | 🟡 | ⬜ |
| **Observability** | Phase 6 — Loki log viewer ✅ · Prometheus/Grafana metrics ✅ · **Jaeger tracing pending** | 🟡 | ⬜ |
| **AI** | MCP server tools over existing APIs (Phase 7) + AI layer (Phase 8) | 🟡 | ⬜ |
| **CI** | CI/CD + security pass (Phase 9) | 🟡 | ⬜ |
| **Perf** | Redis read-through cache for product-service | 🔵 | ⬜ |

### Backlog (testing — deferred, take care of later)

| Area | Task | Priority | Status |
|---|---|---|---|
| **Backend** | Testcontainers unit/integration tests for product + order services (≥80% coverage) | 🔴 | ⬜ |
| **Frontend** | CI step to regenerate/verify Playwright visual baselines per platform | 🟡 | ⬜ |
| **CI** | Load tests (Phase 9) | 🟡 | ⬜ |

> **Building the next one?** See the [**Staff-Role Path**](./staff-path.md) —
> shortlist of which pending items maximize Staff-level signal. **Active pick: Kafka
> events (Phase 5)** — the flagship distributed-systems item, and the direct next
> step after the real gRPC `Charge`. ✅ **done** — see [kafka-events.md](./kafka-events.md).

---

## Session History

| Session | Link | What was built |
|---|---|---|
| product-service-and-skills-setup | [OpenCode Session](https://opncd.ai/share/g9HqARE1) | Phase 0-2: Planning, infra (Docker Compose), Product Service REST (CRUD, validation, OpenAPI, error handling), opencode plugins + skills |
| order-service-and-graphql-bootcamp | `ses_f8cbc723bffeLgUhrQDsDK3Qwf` | Phase 3: Order Service GraphQL — schema + custom `BigDecimal`/`DateTime` scalars, Query/Mutation resolvers, DataLoader (N+1 fix), typed errors (`extensions.code`), GraphiQL, product-service REST integration, README + [GraphQL concepts guide](./graphql-concepts.md) |
| payment-service-grpc-phase-4 | `ses_f81efd3deffepnpkdekguF5zqX` | Phase 4: Payment Service gRPC — 5 RPCs (Charge/Refund/Capture/Void/GetPayment), idempotent money movement (`int64` minor units), state machine, interceptors, health/reflection, grpc-gateway REST + Swagger UI, unit tests (78% svc coverage), layering guide + beginner README |
| web-frontend-phase-5 | `feat/web-frontend` | Frontend: React + Vite storefront `web/` — REST + GraphQL clients, catalog/cart/checkout/order-status pages, Playwright E2E journey + ARIA structure + visual-regression baselines, `docs/frontend-architecture.md` + `docs/testing-strategy.md` |
| web-frontend-apnakart-admin | `ses_f81efd3deffepnpkdekguF5zqX` (continued) · `feat/web-frontend` | Phase 5-6: ApnaKart rebrand (INR / ₹, real product images, demo copy removed) · admin dashboard `/admin` (KPIs, products CRUD, orders view/cancel, payments view/refund via Vite `/v1` proxy) · backend admin APIs (product `imageUrl` + 12 ₹ seeds V3/V4, order `orders`+`orderStats` GraphQL, payment `ListPayments` REST gateway) · unit tests (order Mockito, payment Go) · E2E rebrand + `admin.spec.ts` + regenerated visual baselines · storefront `createOrder` forced INR · README/docs + `run-demo.sh` (admin link, aligned tooling banner, `stop`/`stop --keep-db`). Open new PR #4 |
| infra-containerization + docs-restructure | `ses_f81efd3deffepnpkdekguF5zqX` (continued) · `feat/docker-containers` | Each service containerized (own Dockerfile, healthcheck, log stream) + `docker-compose` app services + `run-demo.sh docker` mode + `docs/infrastructure.md` deployment strategy + this README restructure (PR #5) |
| observability-loki-grafana | `feat/observability-loki-grafana` | Log aggregation: Loki (central log store) + Grafana (UI w/ provisioned dashboard, `svc` label dropdown) via `loki` Docker logging plugin (`x-logging` anchor on apps + kong + keycloak, `svc` labels, `keep-file` preserves `docker compose logs`; plugin reaches Loki via host-published `localhost:3100`) · `run-demo.sh` pre-flight + banner · `docs/infrastructure.md` LogQL cheat-sheet. Log-viewer half of 🟡 Observability done in docker mode; tracing/metrics/correlation-ID deferred until after gRPC Charge |
| kafka-events-phase-5 | `kafka-events-phase-5` | Phase 5 (Kafka): KRaft single-node broker (no ZK), Avro + Confluent Schema Registry (6 events in `common/events/avro`, FULL compat), Kafka UI `:8086` · order-service transactional outbox (`V3__add_outbox.sql`, `FOR UPDATE SKIP LOCKED` relay, OrderCreated/OrderCancelled) · order consumers for PaymentSucceeded/Failed/Voided/Refunded (group `order-saga`, set-once guarded transitions, DLQ via `DefaultErrorHandler`) · payment-service Kafka publish (PaymentSucceeded/Refunded/Voided — Msg Avro wire format, schema-cached SR client) + `orders.cancelled` compensation consumer (group `payment-compensation`, refund/void by state, DLQ + `cancel:<orderId>` idempotency) · correlationId header → MDC on both sides · consumer-lag Grafana panels · `scripts/register-schemas.sh` + `scripts/saga-demo.sh` · docs: `kafka-events.md`, `interview-blueprint.md`, plan/tracking/infra/README updates |

---
