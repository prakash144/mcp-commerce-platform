#!/usr/bin/env bash
# Run the full demo stack: Postgres (Docker) + 3 services + storefront.
# Idempotent: already-running components are left as-is.
# Logs land in ./logs/*.log — tail them to watch startup.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOGS="$ROOT/logs"
mkdir -p "$LOGS"

say() { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }
die() { printf '\033[1;31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }
is_listening() { nc -z -w2 localhost "$1" >/dev/null 2>&1; }

command -v docker >/dev/null || die "docker is required (for Postgres)"
command -v java   >/dev/null || die "java 21 is required"
command -v mvn    >/dev/null || die "maven is required (order-service has no wrapper)"
command -v go     >/dev/null || die "go is required (payment-service)"
command -v node   >/dev/null || die "node is required (frontend)"

say "1/5 Infrastructure — Postgres via Docker"
if docker ps --format '{{.Names}}' | grep -qE '(^|/)postgres'; then
  echo "Postgres already running."
else
  docker compose -f "$ROOT/docker/docker-compose.yml" up -d postgres
  for _ in $(seq 1 40); do
    docker inspect -f '{{.State.Health.Status}}' docker-postgres-1 2>/dev/null | grep -q healthy && break
    sleep 1
  done
  docker inspect -f '{{.State.Health.Status}}' docker-postgres-1 2>/dev/null | grep -q healthy \
    || say "Postgres not healthy yet — check: docker compose -f docker/docker-compose.yml ps"
fi

start_service() {
  local name="$1" port="$2"; shift 2
  if is_listening "$port"; then
    say "${name} already running on :${port}"
  else
    say "Starting ${name} on :${port} → logs/$name.log"
    ( cd "$ROOT/$name" && "$@" ) >"$LOGS/$name.log" 2>&1 &
  fi
}

say "2/5 product-service (REST :8081)"
start_service product-service 8081 ./mvnw spring-boot:run

say "3/5 order-service (GraphQL :8082)"
start_service order-service 8082 mvn spring-boot:run -q

say "4/5 payment-service (gRPC :50051 + REST :8090)"
start_service payment-service 50051 go run ./cmd/server

say "5/5 storefront (web :5173)"
if is_listening 5173; then
  say "Web already running on :5173"
else
  if [ ! -d "$ROOT/web/node_modules" ]; then
    echo "Installing frontend dependencies…"
    ( cd "$ROOT/web" && npm install ) >"$LOGS/web-install.log" 2>&1
  fi
  start_service web 5173 npm run dev
fi

say "Waiting for services to accept traffic…"
url_for_port() {
  case "$1" in
    8081) echo "http://localhost:8081/api/v1/products?page=0&size=1" ;;
    8082) echo "http://localhost:8082/graphql" ;;
    8090) echo "http://localhost:8090/docs" ;;
    5173) echo "http://localhost:5173/" ;;
  esac
}
for port in 8081 8082 8090 5173; do
  up=0
  for _ in $(seq 1 60); do
    if curl -s -o /dev/null --max-time 2 "$(url_for_port "$port")"; then up=1; break; fi
    sleep 1
  done
  if [ "$up" = 1 ]; then echo "  ✓ :$port is up"; else echo "  ✗ :$port not responding — see logs/"; fi
done

cat <<'EOF'

╭────────────────────────────────────────────────────────────────╮
│  Demo is running — open the storefront:                        │
│                                                                │
│    http://localhost:5173   ← frontend (Lumen & Co.)            │
│    http://localhost:8090/docs  ← payment Swagger UI            │
│    http://localhost:8081/swagger-ui.html ← product Swagger UI  │
│    http://localhost:8082/graphiql ← order GraphQL tooling      │
│                                                                │
│  Smoke checks:                                                 │
│    curl "http://localhost:8081/api/v1/products?page=0&size=5"   │
│    grpcurl -plaintext localhost:50051 grpc.health.v1.Health/Check│
│                                                                │
│  E2E tests (needs the stack running — just run it):            │
│    cd web && npx playwright test                               │
│                                                                │
│  Stop everything: kill the java/go/node processes you started; │
│  Postgres keeps running (docker compose ... down to remove it).│
╰────────────────────────────────────────────────────────────────╯
EOF