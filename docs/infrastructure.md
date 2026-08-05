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
│          │                      │       (private, no host access)│
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
│   └────────────┘    └────────────┘    │  :8081     │             │
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
| Infrastructure | Postgres, Redis, Kafka, ZK, Schema Registry | `backend` | No |
| Services | product-service, order-service, payment-service | `backend` | No |

Kong and Keycloak sit on both networks — they are the only entry points into the backend.

---

## File Reference

| File | Purpose |
|---|---|
| `docker/docker-compose.yml` | All container definitions, networks, volumes |
| `docker/kong-config.yml` | Kong declarative config (DB-less mode) — routes added per phase |
| `docker/scripts/init-multiple-dbs.sh` | Creates 4 databases at Postgres first boot |

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

---

## Port Summary

| Port | Service | Protocol | Notes |
|---|---|---|---|
| `5432` | Postgres | PostgreSQL | Host-accessible for debugging (DataGrip, psql) |
| `6379` | Redis | Redis | Host-accessible |
| `9092` | Kafka | Kafka protocol | Host-accessible |
| `8081` | Schema Registry | HTTP | Host-accessible |
| `8080` | Keycloak | HTTP (OIDC) | Host-accessible |
| `8000` | Kong Proxy | HTTP/HTTPS | All client traffic enters here |
| `8001` | Kong Admin API | HTTP | Debugging / plugin config |

Service ports (`product-service-bk`, `order-service`, `payment-service`) are **not exposed to the host** — they're only reachable through Kong on the backend network. This enforces the architecture rule: "only the gateway is internet-facing."

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
