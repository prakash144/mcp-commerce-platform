#!/usr/bin/env bash
# Drive the hybrid event-driven saga end-to-end and print the timeline.
#
#  1. Create an order  -> order-service commits PENDING + writes OrderCreated
#     to its transactional outbox; the relay publishes it to Kafka while it
#     synchronously gRPC-charges payment-service.
#  2. payment-service publishes PaymentSucceeded (fact); order-service consumes
#     it and the order reaches CONFIRMED.
#  3. Cancel the order -> OrderCancelled (outbox); payment-service's
#     compensation consumer (group payment-compensation) refunds the captured
#     payment and publishes PaymentRefunded; order-service transitions the
#     order to REFUNDED (choreographed saga compensation).
#
# Requires: docker-compose stack up (kafka + schema-registry with schemas
#           registered — see register-schemas.sh), services on default ports,
#           jq installed.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE="docker compose -f $ROOT/docker/docker-compose.yml"

ORDER_URL="${ORDER_URL:-http://localhost:8082/graphql}"
PRODUCT_URL="${PRODUCT_URL:-http://localhost:8081/api/v1/products}"
SR_URL="${SCHEMA_REGISTRY_URL:-http://localhost:8089}"

say() { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }
die() { printf '\033[1;31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }
ts() { date +%T; }

command -v jq >/dev/null || die "jq required: brew install jq"

# gql <query> <variables-json> — POSTs a GraphQL request to order-service.
gql() {
  local query="$1" variables="${2:-}"
  if [ -n "$variables" ]; then
    curl -fsS -X POST "$ORDER_URL" -H 'Content-Type: application/json' \
      -d "$(jq -nc --arg q "$query" --argjson v "$variables" '{query:$q, variables:$v}')"
  else
    curl -fsS -X POST "$ORDER_URL" -H 'Content-Type: application/json' \
      -d "$(jq -nc --arg q "$query" '{query:$q}')"
  fi
}

poll_status() { # prints "STATUS<tab>PAYMENT_ID" while awaiting a terminal state
  local expected="$1" status=""
  for _ in $(seq 1 24); do
    status="$(gql 'query($id: ID!){order(id:$id){status paymentId}}' '{"id":"'"$ORDER_ID"'"}')"
    if printf '%s' "$status" | jq -e --arg s "$expected" '.data.order.status == $s' >/dev/null 2>&1; then
      printf '%s' "$status" | jq -r '.data.order.status, .data.order.paymentId // empty' | paste -s -d, -
      return 0
    fi
    sleep 0.5
  done
  printf '%s' "$status" | jq -r '.data.order.status, .data.order.paymentId // empty' | paste -s -d, -
  return 1
}

say "Preflight"
curl -fsS -o /dev/null --max-time 5 -X POST "$ORDER_URL" \
  -H 'Content-Type: application/json' \
  -d '{"query":"{ __typename }"}' 2>/dev/null ||
  die "unreachable: $ORDER_URL (is the stack up?)"
for url in "$SR_URL/subjects" "http://localhost:8086"; do
  curl -fsS -o /dev/null --max-time 5 "$url" 2>/dev/null ||
    die "unreachable: $url (is the stack up?)"
done
echo "  ✓ order graphql, schema-registry, kafka-ui reachable"

PRODUCT_ID="$(curl -fsS --max-time 5 "$PRODUCT_URL?page=0&size=1" | jq -r '(.content // .items)[0].id // empty')"
[ -n "${PRODUCT_ID:-}" ] || die "no product found on $PRODUCT_URL (seed products first)"
echo "  ✓ picked product $PRODUCT_ID"

say "Schema subjects registered"
curl -fsS "$SR_URL/subjects" | jq -r '.[]' | sed 's/^/  /'

say "1) Creating order — PENDING (outbox OrderCreated + sync gRPC charge)"
T0="$(ts)"
ORDER_ID="$(
  gql 'mutation($input: CreateOrderInput!){createOrder(input:$input){id status}}' \
    '{"input":{"items":[{"productId":"'"$PRODUCT_ID"'","quantity":1}],"customerId":"demo-saga","currency":"INR"}}' \
  | jq -r '.data.createOrder.id'
)"
[ -n "$ORDER_ID" ] || die "createOrder failed"
echo "  [$T0] order=$ORDER_ID created"

say "2) Waiting for order CONFIRMED (PaymentSucceeded fact)"
RESULT="$(poll_status CONFIRMED || true)"
Sts="${RESULT%%,*}"
PAY=""
case "$RESULT" in
  *","*) PAY="${RESULT#*,}" ;;
esac
echo "  [$(ts)] order status=$Sts paymentId=${PAY:-n/a}"
case "$Sts" in
  CONFIRMED) echo "  ✓ forward path: OrderCreated(outbox) -> PaymentSucceeded -> CONFIRMED" ;;
  FAILED)    echo "  ✗ order FAILED — investigate order-service logs" ;;
  *)         echo "  ✗ timed out waiting for CONFIRMED" ;;
esac
[ "$Sts" = "CONFIRMED" ] || die "forward saga did not complete"

say "4) Cancelling order — OrderCancelled (outbox) -> refund compensation"
T1="$(ts)"
gql 'mutation($id: ID!){cancelOrder(id:$id){id status}}' '{"id":"'"$ORDER_ID"'"}'
echo "  [$T1] payment-service refunds captured payment, publishes PaymentRefunded"
echo "  [$T1] order-service consumes PaymentRefunded -> order REFUNDED"

say "5) Waiting for order REFUNDED (choreographed compensation)"
RESULT="$(poll_status REFUNDED || true)"
Sts="${RESULT%%,*}"
echo "  [$(ts)] order status=$Sts"
case "$Sts" in
  REFUNDED) echo "  ✓ saga compensation complete: OrderCancelled -> PaymentRefunded -> order REFUNDED" ;;
  *)        echo "  ✗ expected REFUNDED, got $Sts — check payment-service logs / DLQ" ;;
esac
[ "$Sts" = "REFUNDED" ] || die "compensation did not complete"

say "6) Consumer groups: lag after saga (should be 0)"
for group in order-saga payment-compensation; do
  echo "  -- group $group"
  $COMPOSE exec -T kafka kafka-consumer-groups --bootstrap-server kafka:29092 \
    --describe --group "$group" 2>/dev/null | awk 'NR==1 || !/^>/ {print "  " $0}'
done

say "Next"
echo "  Kafka UI: http://localhost:8086  (topics + consumers + schema registry)"
echo "  Grafana:  http://localhost:3001  (Commerce dashboard -> Saga — Kafka events row)"