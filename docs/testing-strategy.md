# Testing Strategy — Commerce Platform

Status: **MVP + M3 resilience + observability (metrics/logs)**
Last updated: 2026-09-15

---

## 1. Guiding principle

> Test the **real system** as early and as often as possible. The UI is verified
> against the **live backend services** (not mocks), and the newest addition — the
> real gRPC Charge from order-service → payment-service — is drilled against the
> running docker stack (not just unit mocks), so a pass proves genuine
> REST → GraphQL → gRPC → Postgres integration.

## 2. Tooling

| Layer | Tool | Scope |
|---|---|---|
| E2E journeys | Playwright (Chromium) | Full user journeys against real services |
| Visual regression | Playwright `toHaveScreenshot` | Pixel diff vs committed baselines |
| Accessibility | Playwright `ariaSnapshot()` + axe-ready roles | Structural/ARIA checks (partially automated) |
| API-level | curl / GraphiQL / Swagger | Ad-hoc contract checks |
| gRPC smoke | `grpcurl` (host, `localhost:50051`) | Exercise payment-service RPCs directly |
| Resilience drill | docker compose `stop/pause/start` + Loki | Deliberate failures of the payment backend (§6) |
| Metrics verification | Prometheus + Grafana dashboards | Watch RED + business + breaker metrics change during drills (§6.6) |
| Component/unit (future) | Vitest + React Testing Library | Cart logic, form validation |
| Per-service integration | Testcontainers (backend) | Product/order service contracts — separate from UI |

## 3. What is automated vs manual

### Automated (Playwright, `web/e2e/`)
- **Journey** — `checkout.spec.ts`: home → catalog → product → cart → checkout →
  order confirmed.
- **Visual baselines** — `checkout.spec.ts`: 5 stable pages pinned
  (`01-home` … `05-checkout`); regenerated with `npx playwright test --update-snapshots`
  only when the change is intentional.
- **Structure** — `checkout.spec.ts`: ARIA snapshot of the home page (banner, nav
  with live cart count, headings, product-card names). The ARIA tree doubles as a
  readable dump for human/agent review.
- **Admin smoke** — `admin.spec.ts`: 4 tests asserting `/admin` KPIs render and the
  Products/Orders/Payments tables load with live, seeded data (no mutations).

### Backend unit tests
- `payment-service`: `go test ./...` — idempotency, state machine, validation, and
  `ListPayments` filter/pagination (service level with a fake repo).
- `order-service`: `mvn test` — `OrderServiceTest` (Mockito): `getOrders`
  (all vs by status/pagination), `getOrderStats` aggregation.
- **To add (M5, §7):** a gRPC in-process-server test proving the retry-collision
  case — "a retried Charge lands exactly one payment".

### Manual (human judgment — do NOT automate these)
- Motion/animation feel, spacing polish, brand consistency.
- Mobile touch ergonomics (eventually automated via device viewports).
- Visual *quality* judgment — pixel tests catch *regressions*, not *design quality*.
- Failure-mode feel — how long a degraded checkout "feels" (§6 drills).

## 4. How to run

```bash
# require: postgres + product-service (:8081) + order-service (:8082) + payment-service (:8090) running
cd web
npx playwright test                # journey + structure + visual diff
npx playwright test --update-snapshots   # regenerate visual baselines (intentional change)
npx playwright show-report         # HTML report incl. traces on failure

# backend unit tests (each in its directory)
cd ../payment-service && go test ./...
cd ../order-service && mvn test
```

Verification loop that worked in practice:
1. `npm run build` (type-checks everything via `tsc -b`).
2. `npm run lint` (oxlint).
3. Boot the stack (docker mode) via `./scripts/run-demo.sh docker`.
4. `npx playwright test` — full journey against the real stack.
5. Screenshots land in `web/e2e/screenshots/` (gitignored) for human review.

## 5. Known limits / next steps

- The **payment leg now goes over real gRPC** (Charge, M2) with **retry ×3 + circuit
  breaker** (M3). Contract-drift between the two services is still caught *manually*
  today — a Phase-9 Buf contract test should own that (§7).
- E2E is not yet exercised against a *degraded* payment service — run the §6 drills
  to cover that gap by hand until a chaos step lands in CI.
- Baselines are machine-specific (`-chromium-darwin` suffix); regenerate after OS
  changes or font swaps.
- Add: mobile viewport project, Vitest for cart math/checkout error mapping,
  axe-core automated scan, and a hosted diff once auth lands.

---

## 6. gRPC Charge — resilience failure-drill playbook

This is a **learning lab**: each row in the table is an edge case a production
engineer must be ready for, how to trigger it locally against the real docker
stack, and the behavior to expect. Run it in order — it doubles as the acceptance
suit for the Charge feature.

**Prerequisites:** M2+M3 merged (real gRPC client + Resilience4j retry/breaker),
stack running in docker mode, product seed `11111111-1111-1111-1111-111111111111`.

### 6.1 Evidence toolkit (run these after each drill)

```bash
# D1 — place an order (capture ORDER_ID from the reply)
curl -s -X POST localhost:8082/graphql -H 'Content-Type: application/json' -d \
 '{"query":"mutation{createOrder(input:{items:[{productId:\"11111111-1111-1111-1111-111111111111\",quantity:1}]}){id status totalAmount currency}}"}'

# D2 — read an order's status
curl -s -X POST localhost:8082/graphql -H 'Content-Type: application/json' -d \
 "{\"query\":\"query{order(id:\\\"<ORDER_ID>\\\"){id status}}\"}"

# D3 — payments side (one row per real charge)
curl -s "localhost:8090/v1/payments?page=0&page_size=5"

# D4 — Loki: the Charge trace across order + payment services
now_ns=$(( $(date +%s) * 1000000000 ))
curl -s -G "localhost:3100/loki/api/v1/query_range" \
  --data-urlencode 'query={svc=~"order-service|payment-service"} |= "Charge"' \
  --data-urlencode "start=$((now_ns - 600000000000))" \
  --data-urlencode "end=$now_ns"

# D5 — order-service resilience events (retries + breaker state)
docker logs docker-order-service-1 --since 3m | grep -iE "retrying|breaker"
```

```bash
# D6 — the failure switchboard (payment-service is the victim of these drills)
DC="docker compose -f docker/docker-compose.yml"
$DC stop payment-service     # powered off   → connection refused
$DC start payment-service    # power back on
$DC pause payment-service    # frozen process → hangs → deadline exceeded
$DC unpause payment-service  # thaw
```

### 6.2 The edge-case matrix

| # | Edge case (production scenario) | Trigger | Expected behavior | Why it matters |
|---|---|---|---|---|
| 1 | Happy path | Checkout with payment up | `CONFIRMED`; one payment row; Loki shows one `Charge code=OK` | Baseline |
| 2 | Payment powered off mid-checkout | D6 `stop` → checkout | 3 retries (recoverable), no hang past deadline, breaker starts counting failures | Server crash |
| 3 | Payment frozen / hung | D6 `pause` → checkout | `DEADLINE_EXCEEDED` treated as recoverable → same retry path; **no hang beyond ~7s on first call** | Slow/deadlocked server |
| 4 | Breaker opens | 10-failure window tests (repeat #2) | After `minimumNumberOfCalls` (3) failures → `breaker CLOSED -> OPEN`; calls fail **instantly** | Stop retry storms hitting a dead service |
| 5 | Fast-fail while open | Immediately after #4, checkout again | Order fails in ~0ms (no 3s deadline wait) | Don't hang users |
| 6 | Recovery closes the breaker | D6 `start` → wait ≥6s → checkout | Half-open probe succeeds → `OPEN -> HALF_OPEN -> CLOSED`; order `CONFIRMED` | Self-healing |
| 7 | Permanent (bad request) rejection | `grpcurl` with empty/blank idempotency key: `grpcurl -plaintext -d '{"idempotency_key":""}' localhost:50051 payment.v1.PaymentService/Charge` | Server `INVALID_ARGUMENT`; client maps to `PermanentPaymentException` → **no retry**, breaker **not** chipped | Don't burn retries on client bugs |
| 8 | Exactly-once across retry collision | any retried drill + D3 | **One** payment row for the order despite 2–3 attempts (same idempotency key) | No double-charge |
| 9 | Amount/idempotency key integrity | Checkout ₹2999.00 → inspect D3 | `amountMinor=299900`, match with order total | Money math exact |
| 10 | Version drift between services | Edit a field in `order-service/src/main/proto/payment.proto`, rebuild | Clear gRPC mismatch failure; knowledge that protos must stay in sync | Contract safety (Phase 9 automates) |

### 6.3 Drill scripts (the 60-second version)

**Drill A — happy path (case 1).** D1 → expect `status: CONFIRMED`. D3 → one
`PAYMENT_STATUS_CAPTURED` row, `amountMinor` = price×qty×100. D5 → **no** retry or
breaker lines.

**Drill B — outage → open → recover (cases 2, 4, 5, 6, 8).**
```bash
DC="docker compose -f docker/docker-compose.yml"
$DC stop payment-service
# 1) one checkout → up to 3 retry lines in D5, then payment fails (order pending/500 until M4)
# 2) fire ≥3 failed checkouts → watch D5 for "breaker CLOSED -> OPEN"
# 3) one more checkout → fails in ~0ms (fast-fail, evidence = no 3s delay)
$DC start payment-service && sleep 6       # wait out the 5s open window (half-open probing)
# checkout again → expect CONFIRMED + D5 shows HALF_OPEN -> CLOSED
# D3 → exactly ONE payment row per order (case 8 ✓)
```

**Drill C — hung service (case 3).** `$DC pause payment-service` → checkout → the
charge surfaces as `DEADLINE_EXCEEDED` (recoverable) instead of UNAVAILABLE; D5
still shows retries. `$DC unpause payment-service` to recover. *Note:* `docker
pause` = SIGSTOP and is your honest stand-in for "process stuck in a deadlock".

**Drill D — permanent rejection (case 7).** `grpcurl -plaintext -d
'{"idempotency_key":""}' localhost:50051 payment.v1.PaymentService/Charge` →
`InvalidArgument`. Client-side: no retry attempt in D5 after a Permanent failure.

### 6.4 Behavior cheat-sheet (status → outcome)

| Trigger on payment | gRPC status | Client throws | Retried? | Breaker counts? | Order ends |
|---|---|---|---|---|---|
| up | `OK` / `CAPTURED` | — | — | no | `CONFIRMED` |
| powered off | `UNAVAILABLE` | Recoverable | yes ×3 | yes → OPEN | pending/500* |
| frozen (pause) | `DEADLINE_EXCEEDED` | Recoverable | yes ×3 | yes | pending/500* |
| bad request | `INVALID_ARGUMENT` | Permanent | **no** | **ignored** | 500* |
| recovered | `OK` / `CAPTURED` | — | — | half-open → closed | `CONFIRMED` |

\*Until M4: `OrderService` maps these to `PENDING`/`FAILED` + a clear user message
instead of an opaque 500. M4 is exactly the "failure-path UI" task.

### 6.5 Metrics verification (Prometheus — the drill leaves evidence)

Open Grafana → **Commerce — Metrics** dashboard, or query Prometheus directly
(`curl "localhost:9090/api/v1/query?query=<expr>"`):

| Drill | Metric to watch | Expected |
|---|---|---|
| Happy path | `commerce_orders_total{status="CONFIRMED"}` `commerce_revenue_minor_total` `commerce_payment_charge_total{outcome="captured"}` | value increments by 1 per checkout |
| Outage | `commerce_retries_total` | counts up during failed attempts |
| Breaker opens/closes | `commerce_circuitbreaker_state` (0=CLOSED, 1=OPEN, 2=HALF_OPEN) | 0 → 1 → (recovery) → 2 → 0 |
| Charge latency | `grpc_server_handling_seconds_bucket{grpc_method="Charge"}` | quantiles in latency panels |
| Runtime pressure | `jvm_memory_used_bytes` / `go_goroutines` | stays flat; spikes visible |

### 6.6 Learning takeaways
- Retry idempotency **key must come from the caller and be preserved across
  attempts** — that is what turns "at-least-once" into "exactly-once". Resending
  is not the same as double-charging when the key is stable.
- Timeouts are what stop *hangs*; the **circuit breaker is what stops retry
  storms** once the outage is real. You need both — one alone is not enough.
- Client bugs (4xx) and server crashes (5xx/transport) must feed **different**
  paths: never retry a permanent rejection; always keep a rejected call from
  opening the breaker.
- These drills are cheap but they are the exact scenarios a Phase-9 chaos/contract
  test automates next.

---

## 7. Automation backlog (Phase 9 → CI)

| Item | Why | Shape |
|---|---|---|
| Order-side gRPC unit/integration test (in-process server) | Prove retry collision = exactly-one Charge without docker | M5: gRPC `inprocess` server + stub client |
| Testcontainers for product + order services | Real Postgres contract tests in CI | JUnit + Testcontainers |
| Buf contract test for order↔payment proto | Both sides drift independently today | `buf` lint + diff between the two proto copies |
| Chaos step | Automate Drill C in CI once per PR | docker compose `pause` on payment during an E2E run |
| Path-filtered pipelines | A payment-only PR shouldn't rebuild/UI-test everything | GitHub Actions per-service path filters |