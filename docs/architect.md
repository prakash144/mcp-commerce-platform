# Commerce Platform — Architecture Document

## 1. Guiding Principle

> **The business domain is the constant. The communication mechanism is the variable.**

Product, Order, and Payment are fixed business capabilities. REST, GraphQL, gRPC,
and MCP are just different doors into the same house. Auth and routing are **edge**
concerns — they live in `auth-service` and `api-gateway`, not duplicated inside every
business service.

- Each protocol is used where it **naturally fits**.
- Business logic lives inside the service layer of each microservice.
- Adapters (REST controllers, GraphQL resolvers, gRPC handlers, MCP tools, gateway
  filters, auth endpoints) are **thin** — they translate a protocol-specific request
  into a service-layer call and back. No business rules live in an adapter.

---

## 2. Overall Architecture (updated with Gateway + Auth)

```
                                   +----------------------+
                                   |      Web Client      |
                                   +----------------------+
                                              |
                                     HTTPS (REST / GraphQL)
                                              |
                                              v
                         +----------------------------------------+
                         |              API Gateway                |
                         |  - TLS termination                      |
                         |  - JWT validation (via Auth Service JWKS)|
                         |  - Rate limiting (Redis token bucket)    |
                         |  - Routing + correlation-ID injection    |
                         +----------------------------------------+
                              |                    |
                     REST (validated)      GraphQL (validated)
                              |                    |
                              v                    v
                    +------------------+   +-------------------+
                    | Product Service  |   |   Order Service   |
                    |      Java        |   |       Java        |------gRPC------+
                    | REST APIs        |   | GraphQL           |  (circuit      |
                    +------------------+   +-------------------+   breaker)     |
                              |                    |                            v
                       PostgreSQL           PostgreSQL              +-------------------+
                              |                    |                |  Payment Service  |
                              |                    |                |       Go          |
                              |                    |                |  gRPC Server      |
                              |                    |                |  (internal only)  |
                              |                    |                +-------------------+
                              |                    |                            |
                              +--------------------+----------------------------+
                                                   |                     PostgreSQL
                                               Kafka Events
                                                   |
                              +--------------------+--------------------+
                              |                                         |
                       Inventory Updated                        Payment Completed
                       Order Created                             Order Cancelled

        +--------------------------------------------------------------------+
        |  Auth Service (OIDC/OAuth2 IdP)                                    |
        |  - Issues signed JWTs (access + refresh)                            |
        |  - Exposes JWKS for stateless validation                            |
        |  - Handles login (Authorization Code + PKCE) and machine auth       |
        |    (Client Credentials, used by mcp-server)                         |
        +--------------------------------------------------------------------+
                 ^                          ^                        ^
                 | issues token             | validates JWT          | client-credentials
            Web Client                  API Gateway              mcp-server
```

**Database-per-service** is unchanged: each service owns its own PostgreSQL
schema/instance. `auth-service` also owns its own store (users, roles, refresh
tokens) — it is a business service in its own right, not a shared database other
services read from directly.

**Network topology:** only `api-gateway` and `auth-service` are internet-facing.
`product-service-bk`, `order-service`, and `payment-service` live on a private network
and trust the identity/claims the gateway injects (`X-User-Id`, `X-User-Roles`,
`X-Correlation-Id`) — enforced via network policy / security groups, not just
convention.

---

## 3. AI / MCP Architecture (unchanged shape, now authenticated)

```
                         +------------------------------------+
                         |  ChatGPT / Claude / AI Assistant    |
                         +------------------------------------+
                                        |
                                  MCP Protocol
                                        |
                           +--------------------------+
                           |   Python MCP Server      |
                           | (client-credentials token|
                           |  obtained from auth-svc) |
                           +--------------------------+
                              |        |         |
                            Tool     Tool       Tool
                              |        |         |
          +-------------------+        |         +------------------+
          |                            |                            |
search_products()              create_order()             refund_payment()
          |                            |                            |
          v                            v                            v
                       API Gateway (validates the MCP server's own JWT)
          |                            |                            |
Product REST API             Order GraphQL                Payment gRPC (internal)
```

The MCP server is a **new consumer**, authenticated the same way any other backend
client would be — it is not granted a special bypass around the gateway or auth.

---

## 4. Authentication & Authorization Flow

**User-facing login (Authorization Code + PKCE):**
1. Web client redirects to `auth-service` login page.
2. User authenticates; `auth-service` issues a short-lived **access token** (JWT,
   5–15 min) and a longer-lived **refresh token**.
3. Client sends the access token as `Authorization: Bearer <jwt>` on every request
   to `api-gateway`.
4. Gateway validates the JWT's signature against `auth-service`'s JWKS endpoint —
   **no network call to auth-service per request**, just a cached public-key check.
5. Gateway extracts claims (`sub`, `roles`) and injects them as headers
   (`X-User-Id`, `X-User-Roles`) before forwarding to the target service.
6. Downstream services trust these headers because the network guarantees only the
   gateway can reach them directly (defense in depth: services *may* still
   re-validate the JWT themselves for extra safety).

**Service-to-service / AI agent auth (Client Credentials):**
1. `mcp-server` authenticates directly with `auth-service` using a client
   ID/secret (no user involved), receiving a scoped access token
   (e.g., `scope: products:read orders:write`).
2. Every MCP tool call attaches this token when calling through the gateway.
3. Tokens are short-lived and refreshed automatically — never a long-lived static
   API key baked into the MCP server.

**Why not call `auth-service` synchronously on every request?**
Because that makes the IdP a single point of failure and adds a network hop's
latency to every single call in the system. JWTs are self-contained and
cryptographically verifiable — validate them locally, and only hit `auth-service`
for the comparatively rare operations: login, refresh, logout/revocation checks.

---

## 5. Communication Matrix (updated)

| Caller | Callee | Protocol | Why |
|---|---|---|---|
| Web Client | Auth Service | OAuth2/OIDC (HTTPS) | Login, token issuance/refresh |
| Web Client | API Gateway | REST / GraphQL (HTTPS) | Single entry point for all business traffic |
| API Gateway | Product Service | REST | Simple resource CRUD |
| API Gateway | Order Service | GraphQL | Flexible, nested queries without over-fetching |
| Order Service | Payment Service | gRPC (+ circuit breaker) | Internal, low-latency, strongly-typed; isolated so a Payment outage can't cascade |
| All services | Kafka | Async events | Decouples services from each other's availability |
| MCP Server | Auth Service | OAuth2 Client Credentials | Machine-to-machine auth, scoped token |
| MCP Server | API Gateway | REST / GraphQL / (gRPC via gateway or direct internal route) | Reuses existing contracts; no new business logic |
| AI Assistant | MCP Server | MCP | Standardized tool-calling interface for LLMs |

---

## 6. End-to-End Flow Example: "Place an Order" (updated)

1. Client sends `Authorization: Bearer <jwt>` + GraphQL `createOrder(input)` to
   **API Gateway**.
2. Gateway validates the JWT, injects `X-User-Id`/`X-User-Roles`, forwards to
   `order-service`.
3. `order-service` resolver calls its service layer, which:
   a. Calls **Product Service (REST)** to validate product & price.
   b. Persists the order (status = `PENDING`).
   c. Publishes `OrderCreated` to Kafka.
   d. Calls **Payment Service (gRPC)** `Charge()` — wrapped in a **circuit breaker**
   with a timeout and a bounded retry (idempotent, using an idempotency key).
4. `payment-service` processes the charge, persists the transaction, publishes
   `PaymentSucceeded` or `PaymentFailed`.
5. `order-service` consumes the payment event, updates order status
   (`CONFIRMED`/`CANCELLED`), publishing `OrderCancelled` on failure — this is the
   **Saga pattern**: no distributed transaction, each step commits locally and
   compensates via events on failure.
6. A single **trace ID**, generated at the gateway, threads through every hop —
   visible as one trace in Jaeger from Web Client through to Kafka consumers.

---

## 7. Distributed Systems & Microservices Patterns — Staff-Level Checklist

This is the full set of patterns a staff engineer is expected to recognize and
apply. Each row notes whether it's **implemented** in this project, **planned**
for a specific phase, or a **stretch/conceptual** goal you should know but we
won't fully build (to keep scope sane for a learning project).

| Pattern | Purpose | Status here | Tooling (per language) |
|---|---|---|---|
| **API Gateway** | Single entry point, edge auth, routing | Planned — Phase 3 | Spring Cloud Gateway or Kong (open decision) |
| **Centralized Auth (OIDC/OAuth2)** | One source of truth for identity, stateless JWT validation | Planned — Phase 3 | Spring Authorization Server or Keycloak (open decision) |
| **Circuit Breaker** | Stop cascading failures when a dependency is down | Planned — Phase 4/6 (Order → Payment) | Resilience4j (Java), `sony/gobreaker` (Go), `pybreaker` (Python) |
| **Retry with backoff + jitter** | Handle transient failures without hammering a struggling service | Planned — Phase 6 | Resilience4j `@Retry`, Go retry libs, `tenacity` (Python) |
| **Timeout / Deadline propagation** | Bound how long a caller waits; prevent thread/connection exhaustion | Planned — Phase 6 (gRPC deadlines already in Phase 5 design) | gRPC context deadlines, Spring `WebClient` timeouts |
| **Bulkhead** | Isolate thread/connection pools per dependency so one slow downstream doesn't starve everything | Planned — Phase 6 | Resilience4j Bulkhead, separate HTTP client pools |
| **Rate Limiting** | Protect backends from abuse/overload | Planned — Phase 3 (gateway) + optionally per-service | Redis token bucket at gateway; Resilience4j `RateLimiter` per service |
| **Load Balancing** | Distribute traffic across instances | Implicit via Docker Compose/K8s Service; conceptual for this scale | K8s Service / Envoy / client-side LB |
| **Service Discovery** | Find instances of a dependency dynamically | Docker Compose DNS for now; conceptual beyond that | Consul/Eureka/K8s DNS |
| **Idempotency Keys** | Safe retries on non-idempotent operations (charges!) | Planned — Phase 5 (`Charge()`) | Idempotency-Key header + dedupe table |
| **Outbox Pattern** | Avoid the dual-write problem (DB write + event publish not atomic) | **Stretch** — worth knowing, adds real complexity | Transactional outbox table + CDC or polling publisher |
| **Saga Pattern (choreography)** | Distributed "transaction" across services via events | Implemented conceptually — Phase 4/7 (Order/Payment flow above) | Kafka events + compensating actions |
| **Dead Letter Queue** | Don't lose/block on poison messages | Planned — Phase 7 | Kafka DLQ topic per consumer group |
| **Schema Registry / Event Versioning** | Prevent producer/consumer contract drift | Planned — Phase 7 | Confluent Schema Registry (Avro) or JSON Schema in `common/contracts` |
| **Distributed Tracing** | Follow one request across every hop/protocol | Planned — Phase 8 | OpenTelemetry + Jaeger |
| **Structured Logging + Correlation ID** | Debuggable, greppable, joinable logs across services | Planned — Phase 8 (started day one per service) | JSON logs, `X-Correlation-Id` header |
| **Health Checks (liveness/readiness) + Graceful Shutdown** | Safe rolling deploys, correct orchestrator behavior | Partially done (product-service `/actuator/health`); extend to all | Spring Actuator, gRPC Health Protocol, custom for Python |
| **12-Factor Config / Secrets Management** | No secrets in code; environment-driven config | Env vars now; Vault planned — Phase 11 | Spring `application.yml` + env, HashiCorp Vault |
| **Contract Testing** | Catch breaking API changes before they hit consumers | **Stretch** | Pact |
| **Blue-Green / Canary Deployment** | Safer releases | **Stretch/conceptual** | K8s + gateway traffic splitting |
| **mTLS / Service Mesh** | Encrypted, authenticated service-to-service traffic without app code changes | **Stretch/conceptual** — noted as the "next level" beyond gateway-only edge auth | Istio / Linkerd |
| **Backpressure / Flow Control** | Prevent producers from overwhelming slow consumers | **Stretch/conceptual** | Reactive streams, Kafka consumer lag monitoring |
| **Caching Strategy (cache-aside)** | Reduce load on hot read paths | Planned (mentioned in original roadmap for product-service) | Redis |
| **Chaos Engineering** | Validate resilience assumptions by injecting real failures | **Stretch/conceptual** — do this once Phase 6 resilience exists, to prove it actually works | Manual fault injection first; Chaos Mesh/Gremlin later |

**Why this matters at the staff level:** none of these patterns are optional
"nice to haves" in a real distributed system — they're the difference between a
system that degrades gracefully and one that cascades into a full outage from a
single slow dependency. This project deliberately builds enough of them for real
hands-on practice, while flagging the rest so you know what exists and when you'd
reach for it.

---

## 8. Observability Architecture (unchanged, now spans the gateway too)

| Concern | Tool | Notes |
|---|---|---|
| Logs | ELK or Loki | Structured JSON, correlation ID in every line, including gateway access logs |
| Metrics | Prometheus + Grafana | Per-service RED metrics (Rate, Errors, Duration); gateway exposes route-level metrics too |
| Tracing | OpenTelemetry + Jaeger | Trace context propagated from the gateway through REST/GraphQL/gRPC/Kafka |
| Health | Actuator / gRPC Health Protocol / liveness endpoints | Used by orchestrator readiness & liveness probes |

---

## 9. Non-Functional Concerns (expanded)

### Security
- Gateway terminates TLS; internal traffic on a private network (mTLS as a stretch goal).
- JWT validation at the gateway; services may re-validate for defense in depth.
- `payment-service` is never directly reachable from outside the private network.
- Secrets (DB passwords, JWT signing keys, Kafka credentials) via Vault/K8s secrets — never committed.
- Rate limiting at the gateway protects all backends from a single choke point.

### Resilience
- Circuit breakers around every cross-service call that could fail independently (Order → Payment first, since it's the highest-stakes dependency).
- Timeouts/deadlines on every outbound call.
- Bulkheads so a slow Payment Service can't exhaust Order Service's thread pool.

### Scalability
- Each service scales independently; gateway and auth-service scale independently too (stateless — JWTs mean no shared session store is required for the gateway itself).
- Kafka absorbs load spikes between Order and downstream consumers.
- Redis used both for gateway rate limiting and Product Service's read-through cache.

---

## 10. Evolution Path (updated)

```
Phase 1   Java REST            (Product Service)
   ↓
Phase 2   Identity & Edge       (Auth Service + API Gateway)
   ↓
Phase 3   Java GraphQL          (Order Service)
   ↓
Phase 4   Go gRPC               (Payment Service, internal-only)
   ↓
Phase 5   Resilience Hardening  (Circuit breakers, retries, bulkheads, rate limits)
   ↓
Phase 6   Kafka Events          (Event-driven integration)
   ↓
Phase 7   Observability         (Tracing/Metrics/Logging across everything, incl. gateway)
   ↓
Phase 8   Python MCP            (AI-facing adapter layer, authenticated like any client)
   ↓
Phase 9   AI Agents             (Anthropic/OpenAI SDK + LangGraph, tool calling, memory)
   ↓
Production-Ready Distributed Commerce Platform
```

Each phase is additive: nothing built in an earlier phase needs to be rewritten to
support a later one, because business logic, transport, and now identity/edge
concerns were kept separate from day one.