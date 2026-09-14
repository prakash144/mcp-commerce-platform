# Infrastructure Setup

---

## Network Topology

```
┌──────────────────────────────────────────────────────────────────┐
│                        FRONTEND NETWORK                          │
│                    (internet-facing, host-accessible)            │
│                                                                  │
│   ┌─────────────┐         ┌────────────┐                         │
│   │   Kong      │         │  Keycloak  │                         │
│   │  :8000      │         │  :8080     │                         │
│   │  (proxy)    │         │  (OIDC)    │                         │
│   │  :8001      │         └─────┬──────┘                         │
│   │  (admin)    │               │                                │
│   └──────┬──────┘               │                                │
│          │                      │                                │
└──────────┼──────────────────────┼────────────────────────────────┘
           │     bridges both     │
┌──────────┼──────────────────────┼────────────────────────────────┐
│          │                      │       BACKEND NETWORK          │
│          │                      │  (host-accessible for local    │
│          │                      │   dev — remove for prod)       │
│          ▼                      ▼                                │
│   ┌────────────┐         ┌────────────┐                          │
│   │  Postgres  │         │    Redis   │                          │
│   │  :5432     │         │   :6379    │                          │
│   │  (4 DBs)   │         └────────────┘                          │
│   └────────────┘                                                 │
│                                                                  │
│   ┌────────────┐    ┌────────────┐    ┌────────────┐             │
│   │  Zookeeper │    │   Kafka    │    │   Schema   │             │
│   │  :2181     │───▶│  :9092     │    │  Registry  │             │
│   └────────────┘    └────────────┘    │  :8089     │             │
│                                       └────────────┘             │
│                                                                  │
│   ┌──────────────── SERVICE (to be built) ─────────────────┐     │
│   │  product-service  order-service  payment-service       │     │
│   │  (REST, Java)     (GraphQL, Java) (gRPC, Go)           │     │
│   └────────────────────────────────────────────────────────┘     │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
```

**Layer breakdown:**

| Layer | Containers | Network | Reachable from host? |
|---|---|---|---|
| Edge | Kong, Keycloak | `frontend` | Yes (:8000, :8001, :8080) |
| Infrastructure | Postgres, Redis, Kafka, ZK, Schema Registry | `backend` | Yes (local dev only — remove for prod) |
| Services | product-service, order-service, payment-service | `backend` | Yes (local dev only — remove for prod) |

Kong and Keycloak sit on both networks — they are the entry points in production.

---

## File Reference

| File | Purpose |
|---|---|
| `docker/docker-compose.yml` | All container definitions, networks, volumes |
| `docker/prometheus/prometheus.yml` | Prometheus scrape targets (all 3 services) |
| `docker/grafana/provisioning/datasources/` | Loki + Prometheus datasource auto-provisioning |
| `docker/grafana/provisioning/dashboards/` | Commerce — Logs + Metrics dashboards |
| `docker/loki/config.yaml` | Loki config (single-binary, ~14 day retention) |
| `docker/kong-config.yml` | Kong declarative config (DB-less mode) |
| `product-service/Dockerfile` | product-service image |
| `order-service/Dockerfile` | order-service image |
| `payment-service/Dockerfile` | payment-service image |

---

## Container Details

### postgres (`postgres:16-alpine`)
- **Network:** backend
- **Host port:** `5432`
- **Databases created at init:** `productdb`, `orderdb`, `paymentdb`, `keycloakdb`
- **Why single instance?** Dev pragmatism — reduces resource usage. Each service connects to its own logical database. In production, you'd split into separate Postgres instances (one per service + one per auth provider).
- **Init mechanism:** `docker/scripts/init-multiple-dbs.sh` is mounted into `/docker-entrypoint-initdb.d/` — Postgres runs all scripts in this directory on first boot only.

### redis (`redis:7-alpine`)
- **Network:** backend
- **Host port:** `6379`
- **Used by:** Kong (rate limiter token bucket), product-service (read cache — later)

### kafka / zookeeper (`confluentinc/cp-server:7.6`)
- **Network:** backend
- **Host port:** `9092`
- **Internal listener:** `kafka:29092` (used by backend containers)
- **External listener:** `localhost:9092` (used from host for debugging)
- **Schema Registry companion:** `:8081` — needed in Phase 7, included now to avoid re-config later
- **Why Zookeeper?** Still the most battle-tested Kafka deployment model. KRaft is the future but ZK is safer for a learning project.

### keycloak (`quay.io/keycloak/keycloak:26.7.0`)
- **Networks:** frontend + backend (bridge)
- **Host port:** `8080`
- **Database:** `keycloakdb` on the shared postgres
- **Admin credentials:** `admin` / `admin`
- **Mode:** `start-dev` (not production — no TLS, no hostname config)

### kong (`kong:3.8-alpine`)
- **Networks:** frontend + backend (bridge)
- **Host ports:** `8000` (proxy), `8001` (Admin API)
- **Mode:** DB-less (`KONG_DATABASE=off`)
- **Config:** `docker/kong-config.yml` mounted as declarative config
- **Why DB-less?** One less database to run. Routes are static and change only when we add a new service — no need for dynamic Admin API usage.

### loki, prometheus & grafana (observability)

**All in one table:**

| Component | Image | Port | Purpose |
|---|---|---|---|
| Loki | `grafana/loki:3.4.2` | `3100` | Central log store (LogQL API) |
| Prometheus | `prom/prometheus:v2.53.2` | `9090` | Metrics store (scrapes all services) |
| Grafana | `grafana/grafana:11.5.2` | `3000` | Dashboards (logs + metrics) |

**Grafana dashboards** (admin/admin):

| Dashboard | Data source | What it shows |
|---|---|---|
| Commerce — Logs | Loki | Service logs, JSON parsed, correlation ID filter |
| Commerce — Metrics | Prometheus | RED (RPS, errors, latency), JVM/Go runtime, business KPIs, circuit breaker |

**How logs reach Loki:** The `grafana/loki-docker-driver` plugin (one-time host install) streams each container's stdout to Loki. Each service carries an `svc` label via the `x-logging` anchor. `keep-file: true` preserves `docker compose logs` behavior.

**How metrics reach Prometheus:** Each service exposes a metrics endpoint (scrape config in `docker/prometheus/prometheus.yml`):

| Service | Metrics endpoint | Format |
|---|---|---|
| product-service | `/actuator/prometheus` | Micrometer (JVM, HTTP, `commerce_*`) |
| order-service | `/actuator/prometheus` | Micrometer (JVM, HTTP, `commerce_*`, circuit breaker) |
| payment-service | `/metrics` | Prometheus client (`grpc_*`, Go runtime) |

### Application services (one container per service — separate log streams)
Each app has its own image (built from source via the service's `Dockerfile`), its own
log stream, and a readiness probe. They run on the `backend` network and reach
Postgres by container name (`postgres`), so no `localhost` wiring in containers.

| Service | Image | Host ports | Depends on | Healthcheck | Env (compose) |
|---|---|---|---|---|---|
| product-service | Maven build → `eclipse-temurin:21-jre-alpine`, non-root `app` | `8081` | postgres (healthy) | `GET /actuator/health` | `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` |
| order-service | Maven build → `eclipse-temurin:21-jre-alpine`, non-root `app` | `8082` | postgres (healthy), product-service (started) | `GET /actuator/health` | `DB_URL` (orderdb), `PRODUCT_SERVICE_URL=http://product-service:8081` |
| payment-service | Go 1.26 build (static, `CGO=0`) → `alpine:3.20`, non-root `app` | `50051` (gRPC), `8090` (REST) | postgres (healthy) | `grpc_health_probe -addr=:50051` | `DB_HOST=postgres`, `DB_NAME=paymentdb`, ports, creds |
| web | `node:22-alpine` + Vite dev server (host source bind-mounted for HMR) | `5173` | — | `GET /` | `WEB_PROXY_PRODUCT`, `WEB_PROXY_ORDER`, `WEB_PROXY_PAYMENT` |

- **Why a container per service?** Independent lifecycles (start/stop/restart/logs per
  service) and closer-to-prod isolation — each service is its own deployable unit.
- **Config is env-driven** — images contain no hardcoded hostnames or secrets; compose
  injects them. On the host (non-docker) run, the Java services default to
  `localhost` via `${DB_URL:...}` / `${PRODUCT_SERVICE_URL:http://localhost:8081}`.
- **web proxy** — the Vite config reads `WEB_PROXY_*` for `/api` `/graphql` `/v1`
  upstreams (container names inside compose, `localhost` ports by default on the host).
- **Postgres remains a single container** (dev pragmatism) with four logical databases;
  `./scripts/run-demo.sh stop --keep-db` keeps it and the `postgres-data` volume.

---

## Port Summary

| Port | Service | Protocol | Notes |
|---|---|---|---|
| `5432` | Postgres | PostgreSQL | Single instance, 4 databases |
| `50051` | Payment Service | gRPC | |
| `6379` | Redis | Redis | |
| `8000` | Kong Proxy | HTTP | Gateway entry point |
| `8001` | Kong Admin API | HTTP | Debugging |
| `8080` | Keycloak | HTTP | OIDC auth (admin/admin) |
| `8081` | Product Service | REST | |
| `8082` | Order Service | GraphQL | |
| `8090` | Payment REST | HTTP | grpc-gateway + Swagger |
| `9092` | Kafka | Kafka | |
| `9090` | **Prometheus** | HTTP | Metrics store |
| `3000` | **Grafana** | HTTP | Dashboards (admin/admin) |
| `3100` | **Loki** | HTTP | Log store |

**Local dev note:** backend network is host-accessible for local development (services, DBs, Kafka). In production, add `internal: true` back to the backend network and route all traffic through Kong.

## Observability — Logs + Metrics

### Logs (Loki)

Open `http://localhost:3000` → **Commerce — Logs** dashboard (service dropdown + correlation ID filter).

Logs are structured JSON (logstash format). MDC fields (`correlationId`, `orderId`, `evt`) are top-level JSON keys, parseable in Grafana via `| json`.

| Goal | LogQL |
|---|---|
| One service | `{svc="order-service"}` |
| Journey trace | `{svc=~"order-service\|payment-service"} \| json \| correlationId="xxx"` |
| Errors only | `{svc="order-service"} \| json \| level="ERROR"` |

---

## Infra Setup & Deployment Strategy

### Units of deployment

Every service ships as its own container image, built from source with a
**multi-stage Dockerfile** (build deps live only in the builder stage; the runtime
stage is a slim base with a **non-root** user, a **healthcheck**, and `EXPOSE` only
what it serves). Postgres is the only infra container this stack needs to run.

| Service | Builder stage | Runtime stage | Readiness |
|---|---|---|---|
| product-service | `maven:3.9-eclipse-temurin-21-alpine` | `eclipse-temurin:21-jre-alpine` (`app`) | `GET /actuator/health` |
| order-service | `maven:3.9-eclipse-temurin-21-alpine` | `eclipse-temurin:21-jre-alpine` (`app`) | `GET /actuator/health` |
| payment-service | `golang:1.26-alpine` (static `CGO_ENABLED=0`) | `alpine:3.20` (`app`) + `grpc-health-probe` | `grpc_health_probe -addr=:50051` |
| web | `node:22-alpine` (`npm ci`) | same image = Vite dev server | `GET /` |

Deployment principles:

- **Config is env-injected, never baked into an image** — compose sets DB URLs/user/pass,
  inter-service base URLs, and the web proxy upstreams. The same image runs anywhere;
  only environment differs.
- **One command to rule them all** — `docker compose` spins identical containers locally and
  remotely; `scripts/run-demo.sh docker` wraps it (build + start + banner).
- **Crash-safe defaults** — `restart: unless-stopped`, healthchecks gate `depends_on`
  ordering, and Postgres data lives in the named `postgres-data` volume (survives
  `down`, wiped only by `down -v`).
- **Out of scope for now** (tracked in the roadmap): horizontal scaling/rolling
  zero-downtime deploys (K8s/compose scale), image registry + tag promotion, secrets
  manager, gateway/auth (Kong/Keycloak) enforced on the frontend path.

### CI/CD strategy

One pipeline per service (thin, deployable independently — this is the point of the
per-service split). A service's image is built, tested, tagged, and pushed *only*
when its own code changes; other services are untouched.

1. **CI (on push/PR to a service path):** unit/type checks — `go test ./...`,
   `mvn test`, `npm run lint` + `npm run build` — then `docker build` the image with
   **cached** Maven/Golang/Node layers (BuildKit cache mounts).
2. **Tag & publish** — tag every image with `git rev-parse --short HEAD` (here: pushed
   to a registry like GHCR; the compose file keeps building from local source until then).
3. **CD (manual approval)** — pull the tagged image and `docker compose up -d <service>`,
   then smoke-check its health endpoint (`curl localhost:<port>/actuator/health`,
   `grpc_health_probe`, `GET /`) before proceeding to the next service.
4. **Web E2E** (Playwright turn instead of CD) is run against a live stack — it is a
   manual step, not part of the deploy gate.

> Pragmatic gates today: PRs must pass CI; deploys are **manual** (no auto-deploy) —
> best for a learning project while Kong/Keycloak and a registry are still TODO.

### Deployment commands (per service)

```bash
export COMPOSE="docker compose -f docker/docker-compose.yml"

# ── build / start everything (recommended: use the script) ──────────────
./scripts/run-demo.sh docker                       # build + up postgres + all 4 services

# ── manage a single service (others keep running) ───────────────────────
$COMPOSE up -d --build product-service             # build + (re)start only product
$COMPOSE restart order-service                     # restart one service
$COMPOSE stop web                                  # stop one service
$COMPOSE up -d payment-service                     # start it again

# ── watch one service's logs (separate per service) ─────────────────────
$COMPOSE logs -f product-service
$COMPOSE logs -f order-service
$COMPOSE logs -f payment-service    # gRPC :50051 + REST :8090 streams
$COMPOSE logs -f web
# or: ./scripts/run-demo.sh docker logs   (all four at once)

# ── health / lifecycle ───────────────────────────────────────────────────
$COMPOSE ps                                     # status + health per container
$COMPOSE up -d                                  # full stack incl. infra (redis/kafka/keycloak/kong)
$COMPOSE down                                  # stop + remove containers, keep data
$COMPOSE down -v                               # also wipe the postgres-data volume
$COMPOSE config --quiet && echo valid          # validate the compose file
```

> Host mode (`./scripts/run-demo.sh`, no `docker` arg) still runs the four services
> as host processes with per-service files in `./logs/*.log` — both modes share the
> same ports, so only one may run at a time.

---

## Starting and Stopping

```bash
# Start everything
docker compose -f docker/docker-compose.yml up -d

# Follow logs for a specific service
docker compose -f docker/docker-compose.yml logs -f kong

# Stop everything
docker compose -f docker/docker-compose.yml down

# Wipe all data (volumes)
docker compose -f docker/docker-compose.yml down -v
```

First boot will take time for Postgres to initialize and create databases. Run `docker compose ps` to check health status.
