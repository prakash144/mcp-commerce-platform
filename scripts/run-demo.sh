#!/usr/bin/env bash
# Run the full demo stack: Postgres (Docker) + 3 services + storefront.
# Idempotent: already-running components are left as-is.
# Logs land in ./logs/*.log — tail them to watch startup.
#
# Usage:
#   ./scripts/run-demo.sh                 start everything
#   ./scripts/run-demo.sh stop            stop services + Postgres
#   ./scripts/run-demo.sh stop --keep-db  stop services, keep Postgres
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOGS="$ROOT/logs"
mkdir -p "$LOGS"

say() { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }
die() { printf '\033[1;31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }
is_listening() { nc -z -w2 localhost "$1" >/dev/null 2>&1; }

# ─── stop mode ───────────────────────────────────────────────────────────
# The demo services listen on these ports (8090 is the payment REST gateway,
# same process as 50051, so killing 50051 covers it).
DEMO_PORTS=(5173 50051 8081 8082 8090)

stop_demo() {
  echo "Stopping demo services…"
  for port in "${DEMO_PORTS[@]}"; do
    pids=$(lsof -ti tcp:"$port" 2>/dev/null || true)
    if [ -n "$pids" ]; then
      kill $pids 2>/dev/null || true
      # graceful shutdown can take a few seconds — escalate to SIGKILL
      for _ in $(seq 1 15); do
        nc -z -w1 localhost "$port" 2>/dev/null || break
        sleep 1
      done
      stragglers=$(lsof -ti tcp:"$port" 2>/dev/null || true)
      if [ -n "$stragglers" ]; then
        kill -9 $stragglers 2>/dev/null || true
      fi
    fi
    if nc -z -w1 localhost "$port" 2>/dev/null; then
      echo "  ✗ :$port still up — force it manually with: lsof -ti tcp:$port | xargs kill -9"
    else
      echo "  ✓ :$port stopped"
    fi
  done
  if [ "${KEEP_DB:-0}" = "0" ]; then
    echo "Stopping Postgres…"
    docker compose -f "$ROOT/docker/docker-compose.yml" down 2>/dev/null || true
  else
    echo "Postgres left running."
  fi
  echo "Done — demo stopped."
  exit 0
}

case "${1:-}" in
  stop)
    [ "${2:-}" = "--keep-db" ] && KEEP_DB=1
    stop_demo
    ;;
esac

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

box_row() { printf '│  %-66s│\n' "$1"; }
box_gap() { printf '│%68s│\n' ''; }
box_h()   { printf '├%68s┤\n' '' | tr ' ' '─'; }

printf '╭%68s╮\n' '' | tr ' ' '─'
box_row 'Demo is running - open the UI:'
box_gap
box_row "$(printf '  %-12s%s' 'Storefront' 'http://localhost:5173        (ApnaKart)')"
box_row "$(printf '  %-12s%s' 'Admin'      'http://localhost:5173/admin   (dashboard)')"
box_gap
box_h
box_row 'Service API tooling (per service):'
box_row "$(printf '%-7s %-13s %-8s %s' 'service' 'API' 'docs' '')"
box_row "$(printf '%-7s %-13s %-8s %s' 'product' 'REST :8081'  'Swagger'  'localhost:8081/swagger-ui.html')"
box_row "$(printf '%-7s %-13s %-8s %s' 'order'   'GraphQL :8082' 'GraphiQL' 'localhost:8082/graphiql')"
box_row "$(printf '%-7s %-13s %-8s %s' 'payment' 'REST :8090'  'Swagger'  'localhost:8090/docs')"
box_row '    └ (gRPC backend on :50051, same process)'
box_gap
box_h
box_row 'Smoke checks (services running):'
box_row '  curl -s "http://localhost:8081/api/v1/products?page=0&size=5"'
box_row '  curl -s "http://localhost:8090/v1/payments?page=0&page_size=5"'
box_row '  grpcurl -plaintext localhost:50051 grpc.health.v1.Health/Check'
box_row '  curl -s -X POST localhost:8082/graphql \'
box_row "  -d '{ orderStats { totalOrders revenue } }'"
box_gap
box_h
box_row 'E2E tests (stack must be running):'
box_row '  cd web && npx playwright test'
box_gap
box_h
box_row 'Stop everything:'
box_row "$(printf '  %-38s%s' './scripts/run-demo.sh stop' '(services + Postgres)')"
box_row "$(printf '  %-38s%s' './scripts/run-demo.sh stop --keep-db' '(Postgres stays up)')"
printf '╰%68s╯\n' '' | tr ' ' '─'