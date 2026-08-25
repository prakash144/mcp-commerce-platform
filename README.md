# Commerce Platform

A production-style commerce platform built to learn and demonstrate modern backend
communication protocols — REST, GraphQL, gRPC, Kafka, and MCP — by progressively
evolving a real microservices system into an AI-accessible platform.

> **Core idea:** the business domain (Product, Order, Payment) stays constant.
> The protocol used to expose it (REST, GraphQL, gRPC, MCP) is the variable.
> See [`architect.md`](./architect.md) for the full reasoning.

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

Full per-service folder layouts are in [`plan.md`](./plan.md).

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
[`architect.md`](./architect.md).

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
- Order GraphQL: `http://localhost:8082/graphql`
- Payment gRPC: `localhost:9090`
- Jaeger UI: `http://localhost:16686`
- Grafana: `http://localhost:3000`

*(Ports above are placeholders — set them to match your `docker-compose.yml`.)*

---

## Roadmap / Phases

| Phase | Focus |
|---|---|
| 0 | Planning |
| 1 | Foundation (repo, docker-compose, empty service skeletons) |
| 2 | Product Service (REST) |
| 3 | Order Service (GraphQL) |
| 4 | Payment Service (gRPC) |
| 5 | Event-Driven Architecture (Kafka) |
| 6 | Observability |
| 7 | MCP Server |
| 8 | AI Layer (Anthropic/OpenAI SDK, LangGraph) |
| 9 | Production Readiness (CI/CD, testing, security) |

See [`plan.md`](./plan.md) for the full phase-by-phase checklist and
protocol-specific best practices.

---

## Session History

| Session | Link | What was built |
|---|---|---|
| product-service-and-skills-setup | [OpenCode Session](https://opncd.ai/share/g9HqARE1) | Phase 0-2: Planning, infra (Docker Compose), Product Service REST (CRUD, validation, OpenAPI, error handling), opencode plugins + skills |

---

## Documentation

- [`plan.md`](./plan.md) — folder structures, phase plan, best practices per protocol
- [`architect.md`](./architect.md) — architecture diagrams, design principles, communication matrix, observability & security design

---

## Learning Outcomes

By completing this project you will have hands-on experience with REST, GraphQL,
gRPC, Kafka, Docker, PostgreSQL, Redis, Python MCP, AI agents, distributed systems
design, and observability tooling.