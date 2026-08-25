---
name: commerce-platform
description: Load project context for the commerce platform — architecture, decisions, current phase, what's built
---

## Project Context

Read these files for full context:
- `docs/plan.md` — phase plan and checklist
- `docs/architect.md` — architecture diagrams and design decisions
- `docs/adr-001-foundation-decisions.md` — tech stack decisions
- `docs/infrastructure.md` — Docker Compose, network topology, ports
- `product-service/README.md` — what's been built (Phase 2 complete)

## Tech Stack

| Service | Language | Protocol | Status |
|---|---|---|---|
| product-service | Java 21 + Spring Boot 4.1 | REST | Phase 2 COMPLETE |
| order-service | Java 21 + Spring GraphQL | GraphQL | NEXT |
| payment-service | Go | gRPC | Pending |
| mcp-server | Python | MCP | Pending |

## Infrastructure

- API Gateway: Kong (DB-less mode)
- Auth: Keycloak + Kong JWT plugin
- Database: PostgreSQL (single instance, multiple DBs)
- Messaging: Kafka + Zookeeper
- Cache: Redis

## Current Status

- Phase 0 (Planning): COMPLETE
- Phase 1 (Foundation): COMPLETE
- Phase 2 (Product Service REST): COMPLETE
- **Next: Phase 3 (Order Service GraphQL)**

## Working Style

- Staff-level trade-off discussions
- Build together, not for me
- Explain decisions before coding
- Test with Swagger UI / curl
- Document in service README.md
