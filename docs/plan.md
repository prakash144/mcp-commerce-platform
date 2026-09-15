# Commerce Platform — Project Plan

---

## Objective

Build a production-style commerce platform that teaches REST, GraphQL, gRPC, Kafka,
and MCP by implementing three microservices with different protocols, then adding an
AI-accessible layer (MCP) on top **without rewriting any existing service**.

The business domain (Product, Order, Payment) stays constant. The communication
protocol used to expose it (REST, GraphQL, gRPC, MCP) is the variable.

---

## 1. Repository Structure (Top Level)

```
commerce-platform/
├── docs/                     # ADRs, API docs, diagrams, runbooks
├── common/                   # Shared contracts & libraries
│   ├── proto/                # Shared .proto contracts (payment.proto etc.)
│   ├── events/                # Kafka event schemas (Avro/JSON Schema)
│   └── libs/                  # Shared Java lib (error codes, tracing headers, DTOs)
├── product-service/          # Java + Spring Boot (REST)
├── order-service/            # Java + Spring GraphQL
├── payment-service/          # Go (gRPC)
├── mcp-server/               # Python (MCP)
├── docker/                   # docker-compose files, per-env overrides
├── scripts/                  # setup.sh, seed-data.sh, migrate-all.sh
└── infrastructure/           # k8s manifests, Prometheus/Grafana/Jaeger configs
```

---

## 2. Microservice Folder Structures

### 2.1 product-service (Java 21 + Spring Boot, REST)

```
product-service/
├── src/
│   ├── main/
│   │   ├── java/com/commerce/product/
│   │   │   ├── ProductServiceApplication.java
│   │   │   ├── config/            # Security, OpenAPI, Kafka, Redis config
│   │   │   ├── controller/        # REST controllers (thin adapters)
│   │   │   ├── service/           # Business logic lives here
│   │   │   ├── repository/        # Spring Data JPA repositories
│   │   │   ├── entity/            # JPA entities
│   │   │   ├── dto/               # Request/response DTOs (never expose entities)
│   │   │   ├── mapper/            # Entity <-> DTO (MapStruct)
│   │   │   ├── exception/         # Custom exceptions + @ControllerAdvice
│   │   │   ├── validation/        # Custom Bean Validation annotations
│   │   │   └── event/             # Kafka producers (ProductCreated, InventoryUpdated)
│   │   └── resources/
│   │       ├── application.yml
│   │       ├── application-dev.yml
│   │       ├── application-prod.yml
│   │       └── db/migration/      # Flyway SQL migrations
│   └── test/java/com/commerce/product/
│       ├── controller/            # MockMvc / WebTestClient tests
│       ├── service/                # Unit tests (JUnit5 + Mockito)
│       └── repository/            # @DataJpaTest
├── Dockerfile
├── pom.xml
└── README.md
```

### 2.2 order-service (Java 21 + Spring GraphQL)

```
order-service/
├── src/
│   ├── main/
│   │   ├── java/com/commerce/order/
│   │   │   ├── OrderServiceApplication.java
│   │   │   ├── config/
│   │   │   ├── resolver/          # @QueryMapping / @MutationMapping classes
│   │   │   ├── service/           # Business logic
│   │   │   ├── repository/
│   │   │   ├── entity/
│   │   │   ├── dto/
│   │   │   ├── dataloader/        # Batching to solve N+1 (product/customer lookups)
│   │   │   ├── mapper/
│   │   │   ├── exception/         # GraphQL error extensions handler
│   │   │   ├── client/            # gRPC stub client -> payment-service
│   │   │   └── event/             # Kafka producers (OrderCreated, OrderCancelled)
│   │   └── resources/
│   │       ├── graphql/
│   │       │   └── schema.graphqls
│   │       ├── application.yml
│   │       └── db/migration/
│   └── test/java/com/commerce/order/
├── Dockerfile
├── pom.xml
└── README.md
```

### 2.3 payment-service (Go, gRPC)

```
payment-service/
├── cmd/
│   └── server/
│       └── main.go            # entrypoint, wires everything
├── internal/
│   ├── config/                # env/config loading
│   ├── server/                 # gRPC server bootstrap, interceptor chain
│   ├── handler/                 # gRPC method implementations (thin adapters)
│   ├── service/                 # business logic (Charge, Refund, Capture...)
│   ├── repository/              # Postgres access (pgx/sqlc)
│   ├── model/
│   ├── middleware/              # auth, logging, recovery, retry interceptors
│   └── event/                   # Kafka producer/consumer (PaymentSucceeded, etc.)
├── proto/
│   └── payment.proto
├── pkg/generated/               # protoc-gen-go output (checked in or generated in CI)
├── migrations/
├── Dockerfile
├── go.mod / go.sum
└── README.md
```

### 2.4 mcp-server (Python, MCP)

```
mcp-server/
├── src/mcp_server/
│   ├── __init__.py
│   ├── server.py               # MCP server bootstrap (stdio/HTTP transport)
│   ├── tools/                  # One module per tool group
│   │   ├── product_tools.py    # search_products, get_product
│   │   ├── order_tools.py      # create_order, cancel_order, track_order
│   │   └── payment_tools.py    # refund_payment, payment_status
│   ├── clients/                # Thin clients calling existing services
│   │   ├── product_client.py   # REST client
│   │   ├── order_client.py     # GraphQL client
│   │   └── payment_client.py   # gRPC client
│   ├── schemas/                 # Pydantic models = JSON Schema for each tool
│   ├── auth/                    # Token/credential handling
│   ├── config.py
│   └── exceptions.py            # Maps downstream errors -> MCP error format
├── tests/
├── pyproject.toml
├── Dockerfile
└── README.md
```

> **Key design rule:** `mcp-server` contains **zero business logic**. Every tool is a
> thin wrapper that calls the existing REST/GraphQL/gRPC APIs. This is what lets you
> add AI access without rewriting Phases 1–6.

---

## 3. Phase-by-Phase Execution Plan

| Phase | Deliverable | Definition of Done |
|---|---|---|
| 0 | Planning | Repo scaffolded, tech stack confirmed, ADR-001 written |
| 1 | Foundation | Docker Compose up (Postgres, Redis, Kafka) + empty service skeletons boot |
| 2 | Product Service (REST) | Full CRUD + search, OpenAPI docs generated, ≥80% unit test coverage |
| 3 | Order Service (GraphQL) | Schema published, resolvers + DataLoader, calls Payment via gRPC |
| 4 | Payment Service (gRPC) | Proto defined, 5 RPCs implemented, interceptors for auth/logging/retry |
| 5 | Event-Driven Architecture | All 7 events flowing through Kafka, idempotent consumers |
| 6 | Resilience + Observability | 🔶 **done (partial):** Resilience4j retry/breaker on `Charge`, structured JSON logs → Loki, Prometheus metrics → Grafana dashboards, persisted idempotency keys + automated PENDING-order retry (backoff/attempt cap → FAILED). **Pending:** distributed tracing (Jaeger/Tempo), chaos drill in CI |
| 5.5 | Concurrency & Consistency | **Planned (ADR-002):** exactly-once settlement guard (`UPDATE … WHERE status='PENDING'` + `@Version`), `SKIP LOCKED` on the retry job (**multi-instance-safe**), client `idempotencyKey` on `createOrder`, bulkhead on the Charge RPC, server-side pagination cap (`first ≤ 100`) |
| 6.5 | Security | **Planned (ADR-002):** threat-model-first — Keycloak + Kong JWT authn (user PKCE + MCP client-credentials), gateway strips spoofable `X-User-Id`, service-level ownership authz, rate limiting, payment-service network isolation, SAST/dependency/secrets scanning, MCP prompt-injection guardrails |
| 7 | MCP Server | 7 tools implemented, callable from Claude Desktop / claude.ai |
| 8 | AI Layer | Agent using Anthropic SDK + tool calling against MCP server |
| 9 | Production Readiness | CI/CD pipeline, integration + load tests, rate limiting, security review |

### Suggested order of work inside each phase
1. Write the contract first (OpenAPI YAML / GraphQL SDL / .proto / MCP JSON Schema).
2. Generate/scaffold code from the contract where possible.
3. Implement service layer with unit tests before wiring the adapter (controller/resolver/handler).
4. Add integration tests using **Testcontainers** (Postgres, Kafka) before moving to next phase.
5. Wire observability (logging + tracing) as you go — not as an afterthought in Phase 6.

---

## 4. Market-Standard Best Practices (by protocol)

### REST (product-service)
- Version the API: `/api/v1/products`.
- Never expose JPA entities directly — always DTOs.
- Use `@ControllerAdvice` for a single, consistent error response shape (`type`, `title`, `status`, `detail` — RFC 7807 Problem Details).
- Pagination via `Pageable` + `Page<T>`, expose `page`, `size`, `totalElements`.
- Idempotency keys on `POST` where retries are expected.
- OpenAPI generated automatically (springdoc-openapi), not hand-written.

### GraphQL (order-service)
- Schema-first (`schema.graphqls`), not code-first — the schema is the contract.
- Solve N+1 with `DataLoader`, never call downstream per-item in a loop.
- Use input types for mutations (`CreateOrderInput`), not raw scalars.
- Return typed errors via `error.extensions` (`code`, `classification`) instead of generic exceptions.
- Avoid deeply nested queries without depth/complexity limiting (protect against abusive queries).

### gRPC (payment-service)
- Proto files are the source of truth; version with package names (`payment.v1`).
- Always set deadlines on outgoing calls; propagate context cancellation.
- Use interceptors for cross-cutting concerns (auth, logging, metrics) — never inline in handlers.
- Prefer unary RPCs unless streaming is a genuine requirement (e.g., `GetStatus` could be server-streaming for live updates).
- Implement the gRPC Health Checking Protocol for readiness/liveness probes.

### Kafka (event-driven layer)
- Register event schemas (Avro or JSON Schema) in a schema registry — never send raw untyped JSON in prod.
- Producers should be idempotent (`enable.idempotence=true`); consumers should be safe to re-process (idempotent consumers or dedupe keys).
- Use a dead-letter topic per consumer group for poison messages.
- Event names are past-tense facts (`OrderCreated`, not `CreateOrder`) — this is already reflected in the roadmap's event list.

### MCP (mcp-server)
- Each tool has a single, unambiguous responsibility and a strict JSON Schema for inputs/outputs.
- Tools are adapters only — they call existing REST/GraphQL/gRPC services, never touch the database directly.
- Tool descriptions should be written for an LLM's benefit — plain, explicit, with examples of when to call it.
- Standardize error shape across tools so the calling agent can reason about failures consistently.
- Auth: propagate a scoped token/API key from the MCP client through to downstream services; don't hardcode credentials.

### Cross-cutting (all services)
- 12-factor config: all environment-specific values via env vars, never hardcoded.
- Structured (JSON) logging with a correlation/trace ID propagated across REST → GraphQL → gRPC → Kafka.
- Health checks: `/actuator/health` (Spring), gRPC health protocol (Go), MCP server liveness endpoint.
- CI: lint + unit tests + Testcontainers integration tests on every PR; build & push Docker images on merge to main.

---

## 5. Milestone Checklist (copy into your issue tracker)

- [x] Phase 0–4: foundation → product (REST) → order (GraphQL) → payment (gRPC) complete
- [x] Phase 5 (resilience+idempotency): retry ×3 + circuit breaker on Charge done; **idempotency keys persisted on orders + automated PENDING retry** done (charge.attempts, next_retry_at, FAILED terminal state); **Kafka events still open**
- [x] Phase 6: Logs (Loki) + Metrics (Prometheus/Grafana) done; **tracing (Jaeger/Tempo) pending**
- [ ] Phase 5.5 (Concurrency & Consistency): exactly-once settlement guard, SKIP LOCKED, createOrder idempotency, bulkhead, pagination cap — **planned, ADR-002**
- [ ] Phase 6.5 (Security): threat model, Keycloak+Kong JWT authn, authz, rate limiting, supply-chain + secrets scanning, MCP guardrails — **planned, ADR-002**
- [ ] Phase 7: mcp-server exposes 7 tools, tested from Claude Desktop
- [ ] Phase 8: Agent built with Anthropic SDK using MCP tools + memory
- [ ] Phase 9: CI/CD, load tests, rate limiting, security pass complete