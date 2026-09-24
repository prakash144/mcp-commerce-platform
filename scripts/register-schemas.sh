#!/usr/bin/env bash
# Register order/payment event schemas in Confluent Schema Registry and create
# the corresponding Kafka topics with the intended partition count.
#
# Requires: docker compose stack up (schema-registry on :8089, kafka running),
#           jq installed. Also requires an avro tool for validation — the
#           schema JSON is validated against the registry on submit anyway.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EVENTS_DIR="$ROOT/common/events/avro"
COMPOSE="docker compose -f $ROOT/docker/docker-compose.yml"
SR_URL="${SCHEMA_REGISTRY_URL:-http://localhost:8089}"   # host-published SR port
PARTITIONS="${TOPIC_PARTITIONS:-3}"

# "schema-file topic-name" pairs (kept parallel-array free so the script runs on
# bash 3.2, macOS's default /usr/bin/env bash — no `declare -A`).
SCHEMAS=(
  "OrderCreated.avsc orders.created"
  "OrderCancelled.avsc orders.cancelled"
  "PaymentSucceeded.avsc payments.succeeded"
  "PaymentFailed.avsc payments.failed"
  "PaymentVoided.avsc payments.voided"
  "PaymentRefunded.avsc payments.refunded"
)

command -v jq >/dev/null || { echo "error: jq required"; exit 1; }

echo "== Creating topics (${PARTITIONS} partitions each) =="
for entry in "${SCHEMAS[@]}"; do
  topic="${entry##* }"   # part after the space
  if $COMPOSE exec -T kafka kafka-topics --bootstrap-server kafka:29092 \
       --create --if-not-exists --topic "$topic" --partitions "$PARTITIONS" \
       --replication-factor 1 >/dev/null 2>&1; then
    echo "  ✓ topic $topic ready"
  else
    echo "  ✗ failed to create topic $topic (kafka up?)"
  fi
done

echo "== Registering schemas (compatibility=FULL) =="
for entry in "${SCHEMAS[@]}"; do
  file="${entry%% *}"   # part before the space
  topic="${entry##* }"
  subject="$topic-value"   # per-topic-value default naming strategy
  schema_path="$EVENTS_DIR/$file"

  # Guardrail: schema evolution must stay fully compatible (FULL) so that adding
  # a field to a fact never breaks existing consumers.
  curl -fsS -X PUT "$SR_URL/config/$subject" \
    -H 'Content-Type: application/json' \
    -d '{"compatibility":"FULL"}' -o /dev/null || echo "  (subject $subject new — compat config skipped)"

  response="$(
    curl -fsS -X POST "$SR_URL/subjects/$subject/versions" \
      -H 'Content-Type: application/vnd.schemaregistry.v1+json' \
      -d "$(jq -n --arg s "$(cat "$schema_path")" '{schema: $s}')"
  )" || { echo "  ✗ $file → $subject rejected"; continue; }

  id="$(printf '%s' "$response" | jq -r '.id')"
  echo "  ✓ $file → $subject (id=$id)"
done

echo "== Done. Browse topics/schemas: http://localhost:8086 (Kafka UI) =="