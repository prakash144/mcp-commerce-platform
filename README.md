# Commerce Platform

A production-style commerce platform built to learn and demonstrate modern backend
communication protocols — REST, GraphQL, gRPC, Kafka, and MCP — by progressively
evolving a real microservices system into an AI-accessible platform.

> **Core idea:** the business domain (Product, Order, Payment) stays constant.
> The protocol used to expose it (REST, GraphQL, gRPC, MCP) is the variable.
> See [`HLD Architect`](./docs/architect.md) for the full reasoning.

---

## What this is

Three business microservices, each intentionally built with a different protocol,
integrated via Kafka events, fully observable, and finally exposed to AI agents
through an MCP server — without rewriting any of the existing services.

| Service | Language | Protocol | Responsibility |
|---|---|---|---|
| `product-service` | Java 21 + Spring Boot | REST | Product catalog, search, inventory |
| `order-service` | Java 21 + Spring GraphQL | GraphQL | Order lifecycle, customer orders |
| `payment-service` | Go | gRPC | Charge, refund, capture, payment status |
| `mcp-server` | Python | MCP | Exposes the above as AI tools (adapter only, no business logic) |

---

## Tech Stack

| Layer | Technology |
|---|---|
| REST | Java 21 + Spring Boot |
| GraphQL | Java 21 + Spring GraphQL |
| gRPC | Go |
| MCP | Python |
| Database | PostgreSQL (one per service) |
| Cache | Redis |
| Messaging | Kafka |
| API Docs | OpenAPI |
| Containers | Docker / Docker Compose |
| Monitoring | Prometheus + Grafana |
| Logging | ELK / Loki |
| Tracing | OpenTelemetry + Jaeger |

---

## Repository Structure

```
commerce-platform/
├── docs/                     # ADRs, API docs, diagrams, runbooks
├── common/                   # Shared proto contracts, event schemas, libs
├── product-service/          # Java + Spring Boot (REST)
├── order-service/            # Java + Spring GraphQL
├── payment-service/          # Go (gRPC)
├── mcp-server/                # Python (MCP)
├── docker/                    # docker-compose files
├── scripts/                   # setup, seed-data, migration scripts
└── infrastructure/             # k8s manifests, monitoring configs
```

Full per-service folder layouts are in [`plan doc`](./docs/plan.md).

---

## Architecture at a Glance

```
Web Client
   |
REST / GraphQL
   |
Product Service (REST) ─┐
Order Service (GraphQL) ─┼─> Kafka Events ─> Consumers
Payment Service (gRPC)  ─┘        |
                           Jaeger / Prometheus / Loki
```

AI agents reach the same platform through a Python MCP server that wraps the
existing REST/GraphQL/gRPC APIs as tools — no duplicated business logic:

```
Claude / ChatGPT ──MCP──> mcp-server ──> Product REST / Order GraphQL / Payment gRPC
```

Full diagrams, communication matrix, and an end-to-end request walkthrough are in
[`HLD Architect`](./docs/architect.md).

---

## Getting Started

```bash
git clone <repo-url>
cd commerce-platform

# Bring up infra + services
docker compose -f docker/docker-compose.yml up -d

# Run DB migrations (per service)
./scripts/migrate-all.sh

# Seed sample data
./scripts/seed-data.sh
```

Once up:
- Product REST API: `http://localhost:8081/api/v1/products`
- Order GraphQL: `http://localhost:8082/graphql` (interactive editor: `http://localhost:8082/graphiql`)
- Payment gRPC: `localhost:50051` (REST gateway + Swagger UI: `localhost:8090`)
- Jaeger UI: `http://localhost:16686`
- Grafana: `http://localhost:3000`

Note: order-service's `createOrder` calls product-service to validate products and snapshot
their price — run both services (and Postgres) for full end-to-end flows. Per-service READMEs:
[product-service](./product-service/README.md) · [order-service](./order-service/README.md).

*(Ports above are placeholders — set them to match your `docker-compose.yml`.)*

---

## Roadmap / Phases

| Phase | Focus | Status |
|---|---|---|
| 0 | Planning | ✅ |
| 1 | Foundation (repo, docker-compose, empty service skeletons) | ✅ |
| 2 | Product Service (REST) | ✅ Core (CRUD, OpenAPI, error handling) |
| 3 | Order Service (GraphQL) | ✅ Core (create/cancel/query, DataLoader, scalars, GraphiQL; tests + payment gRPC pending) |
| 4 | Payment Service (gRPC) | ✅ |
| 5 | Event-Driven Architecture (Kafka) | ⬜ |
| 6 | Observability | ⬜ |
| 7 | MCP Server | ⬜ |
| 8 | AI Layer (Anthropic/OpenAI SDK, LangGraph) | ⬜ |
| 9 | Production Readiness (CI/CD, testing, security) | ⬜ |

See [`plan.md`](./plan.md) for the full phase-by-phase checklist and
protocol-specific best practices.

---

## Todos

> Legend: 🔴 **Must** complete (gates the working demo) · 🟡 **Should** (production-style) · 🔵 **Nice-to-have**

| Area | Task | Priority | Status |
|---|---|---|---|
| **Frontend** | Build React + Vite storefront (`web/`) — catalog, cart, checkout, order status | 🔴 | ⬜ |
| **Backend** | Replace order-service `StubPaymentClient` with a real gRPC `Charge` → payment-service `:50051` | 🔴 | ⬜ |
| **Backend** | Testcontainers unit/integration tests for product + order services (≥80% coverage) | 🔴 | ⬜ |
| **Edge** | Kong API Gateway — routes for product/order (+ payment REST), JWT plugin, rate limiting | 🟡 | ⬜ |
| **Edge** | Keycloak — realm, web client (PKCE), MCP client-credentials, JWKS wired to Kong | 🟡 | ⬜ |
| **Events** | Kafka events (Phase 5) — OrderCreated/Cancelled, PaymentSucceeded/Failed, + DLQ | 🟡 | ⬜ |
| **Observability** | Phase 6 — Jaeger tracing, Prometheus/Grafana, structured logs with correlation ID | 🟡 | ⬜ |
| **AI** | MCP server tools over existing APIs (Phase 7) + AI layer (Phase 8) | 🟡 | ⬜ |
| **CI** | CI/CD, load tests, security pass (Phase 9) | 🟡 | ⬜ |
| **Frontend** | Admin dashboard — product management, order view, refunds | 🔵 | ⬜ |
| **Perf** | Redis read-through cache for product-service | 🔵 | ⬜ |

---

## Session History

| Session | Link | What was built |
|---|---|---|
| product-service-and-skills-setup | [OpenCode Session](https://opncd.ai/share/g9HqARE1) | Phase 0-2: Planning, infra (Docker Compose), Product Service REST (CRUD, validation, OpenAPI, error handling), opencode plugins + skills |
| order-service-and-graphql-bootcamp | `ses_f8cbc723bffeLgUhrQDsDK3Qwf` | Phase 3: Order Service GraphQL — schema + custom `BigDecimal`/`DateTime` scalars, Query/Mutation resolvers, DataLoader (N+1 fix), typed errors (`extensions.code`), GraphiQL, product-service REST integration, README + [GraphQL concepts guide](./docs/graphql-concepts.md) |
| payment-service-grpc-phase-4 | `ses_f81efd3deffepnpkdekguF5zqX` | Phase 4: Payment Service gRPC — 5 RPCs (Charge/Refund/Capture/Void/GetPayment), idempotent money movement (`int64` minor units), state machine, interceptors, health/reflection, grpc-gateway REST + Swagger UI, unit tests (78% svc coverage), layering guide + beginner README |

---

## Documentation

- [`Plan`](./docs/plan.md) — folder structures, phase plan, best practices per protocol
- [`HLD Architect`](./docs/architect.md) — architecture diagrams, design principles, communication matrix, observability & security design
- [`GraphQL concepts (beginner guide)`](./docs/graphql-concepts.md) — REST-vs-GraphQL, resolvers, scalars, DataLoader/N+1, error shape (start here before reading order-service)
- [`product-service/README.md`](./product-service/README.md) — REST service specifics
- [`order-service/README.md`](./order-service/README.md) — GraphQL service specifics + internal flowcharts

---

## Learning Outcomes

By completing this project you will have hands-on experience with REST, GraphQL,
gRPC, Kafka, Docker, PostgreSQL, Redis, Python MCP, AI agents, distributed systems
design, and observability tooling.