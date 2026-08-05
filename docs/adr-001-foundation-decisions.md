# ADR-001: Foundation Decisions — Repo Structure, Gateway, Auth, Build Tool

**Status:** Accepted  
**Date:** 2026-07-27  
**Driver:** Learning project for staff-level backend engineering prep (REST, GraphQL, gRPC, Kafka, MCP)

---

## Context

We are building a commerce platform with three microservices (Product/REST, Order/GraphQL, Payment/gRPC), an MCP AI layer, and full observability. Before writing any code, foundational decisions are needed that affect every phase.

Decisions to make:
1. Monorepo vs polyrepo
2. API Gateway choice
3. Auth provider and pattern
4. Java build tool

---

## Decision 1: Monorepo

**Chosen: Monorepo**

The project uses multiple languages (Java, Go, Python) and shares contracts (`.proto` files, Avro/JSON event schemas) in a `common/` directory. A monorepo keeps all contracts in one version, avoids cross-repo publishing pipelines, and lets us make atomic changes across services.

Counter-argument (polyrepo) would make sense for multiple independent teams with separate release cycles — not our case as a solo learner.

Consequences:
- Shared contracts live in `common/` and are referenced by all services
- CI pipelines use path filters (`../product-service-bk/**`, `../payment-service/**`) to avoid building everything on every commit
- `CODEOWNERS` pattern recommended if this grows

---

## Decision 2: API Gateway

**Chosen: Kong (Open Source)**

REST, GraphQL, and gRPC traffic all flow through the same gateway. Kong natively supports all three protocols, has built-in plugins for JWT validation, rate limiting (Redis token bucket), and routing. It runs as a separate process — no coupling to any service's language.

Alternatives considered:
- **Spring Cloud Gateway** — stays in Java ecosystem but adds complexity for gRPC passthrough and requires writing custom rate-limit/auth logic
- **Envoy** — more flexible but steeper learning curve; better suited for a service mesh context

Kong fits the polyglot nature of this project and lets us offload edge concerns (TLS, auth, rate limiting) into configuration rather than code.

Consequences:
- Kong must be included in `docker-compose.yml` with its database (Postgres or Cassandra — we'll use Postgres)
- gRPC routing needs Kong's `grpc` or `grpc-web` proxy enabled
- JWT plugin will validate tokens against Keycloak's JWKS endpoint
- Gateway adds one network hop — acceptable for learning; in prod you'd run Kong alongside your services on the same K8s node

---

## Decision 3: Auth Provider

**Chosen: Keycloak + Kong JWT plugin**

Pattern:
- **Keycloak** handles OIDC flows: login, token issuance, refresh, client credentials grant
- Keycloak exposes a **JWKS endpoint** for stateless JWT validation
- **Kong's JWT plugin** validates tokens at the gateway using cached JWKS keys — no network call to Keycloak per request
- Gateway injects `X-User-Id`, `X-User-Roles`, `X-Correlation-Id` headers for downstream services
- `mcp-server` will authenticate via Client Credentials grant (machine-to-machine)

Why not Spring Authorization Server:
- More code to write, more surface area for security bugs
- Adds no learning value for our goal (distributed systems patterns, not OIDC internals)
- Keycloak is the pragmatic "get it working" choice

Consequences:
- Keycloak runs as a container in `docker-compose.yml`
- A realm + client must be configured for user-facing auth (PKCE) and another for MCP server (Client Credentials)
- Downstream services trust the gateway-injected headers; defense-in-depth would re-validate JWTs at the service level (optional for now)

---

## Decision 4: Java Build Tool

**Chosen: Maven**

The Java services use standard Spring Boot + Flyway + MapStruct + Protobuf — nothing that benefits from Gradle's flexibility. Maven's convention-over-configuration and predictable XML format keep build scripts minimal and readable.

For this project, the build tool is infrastructure, not architecture. Time spent on Groovy/Kotlin DSL is better spent on distributed system patterns.

Consequences:
- All Java services use `pom.xml` with a shared parent POM or BOM in `common/`
- Protobuf compilation uses `protobuf-maven-plugin` or OS-level protoc

---

## Decisions Log

| # | Decision | Choice | Reasoning |
|---|---|---|---|
| 1 | Repo structure | Monorepo | Shared contracts across languages; solo project speed |
| 2 | API Gateway | Kong (CE) | Polyglot support, built-in rate-limit + JWT plugins |
| 3 | Auth | Keycloak + Kong JWT | Battle-tested OIDC; stateless validation at edge |
| 4 | Build tool | Maven | Standard Spring Boot; no need for Gradle's flexibility |

---

## Status of Phases

- Phase 0 (Planning): Complete — ADR-001 documents all foundational decisions
- Phase 1 (Foundation): Next — Docker Compose + empty service skeletons
