# Staff-Role Path — Must-Pick Pending Items

**Purpose:** decide *which* pending feature to build next so the work maximizes
Staff-level backend engineering signal — the topics interviewers and Staff-eng
panels actually probe, that also build on the foundation already in this repo
(Phases 0–4 + admin frontend + containerization → see [`README Roadmap`](../README.md#roadmap--phases)).

**How to use this doc:** you build, opencode coaches. Pick one item from Tier 1,
implement it yourself, and treat the "Definition of done" per item as the bar to
hit before moving on. Don't parallel-track.

---

## Tier 1 — build these yourself (highest Staff signal)

### 1. Real gRPC Charge + distributed resilience (order → payment) — *do this first*

Smallest scope, immediate payoff, unblocks the one 🔴 hole in the E2E flow. What
makes it Staff-level: it's not "call the RPC" — it's **timeouts, retries, circuit
breaker (Resilience4j), and idempotency-key handling when retries collide**. You
already have the idempotency machinery in payment-service; now exercise it across
a network boundary.

- **You build:** the gRPC client, breaker + retry policy, and the failure-path UI
  (order marked `PENDING`/`RETRYING`, no double-charge on retry).
- **Definition of done:** a retried request lands exactly one `Charge`; a payment
  outage degrades checkout to a clear user message instead of a hang; order reaches
  `PAID` once the payment recovers.
- **Coach:** resilience patterns, idempotency collision scenarios, code review.

### 2. Kafka event-driven architecture (Phase 5) — *the flagship*

The single most-asked distributed-systems topic. To hit the Staff bar, don't stop
at "publish OrderCreated" — implement the **transactional outbox pattern** (write
the event and the order in one DB transaction; a relay publishes it), **idempotent
consumers**, **consumer groups**, and a **DLQ with replay tooling**. This is where
you demonstrate "asynchronous consistency without losing money."

- **You build:** outbox table + relay, consumers, DLQ, replay script.
- **Definition of done:** an order created during a Kafka outage still gets its
  event published once Kafka returns (no `poll-then-publish` gap, no duplicates);
  a poisoned message ends in the DLQ and can be replayed without manual surgery.
- **Coach:** delivery/ordering guarantees, outbox transaction boundaries, review.

### 3. Observability (Phase 6) — *the "can I debug prod" proof*

Correlation ID threaded through REST → GraphQL → gRPC → Kafka, OpenTelemetry spans,
plus one Grafana dashboard that answers "is checkout healthy?" with a single
question.

- **You build:** header propagation, spans, metrics, dashboard.
- **Definition of done:** given a bad `orderId` you can trace the full request
  journey across all three services from one `traceId`/`correlationId`; the
  dashboard surfaces a degraded checkout before customers complain.
- **Coach:** propagation through async boundaries (this subtlety *is* the learning),
  span/metric naming, review.

### 4. CI/CD + integration testing (Phase 9) — *the "ships safely" proof*

Path-filtered pipelines (per service), **Testcontainers** for the Java services,
and a **contract test** for the order→payment boundary. As a Staff engineer, "no
CI, manual test checklist" is a red flag — this is the credibility feature.

- **You build:** the pipeline and test suites.
- **Definition of done:** a PR touching only `payment-service/` builds only that
  service; the order→payment contract test fails loudly if either side drifts;
  baselines for the Playwright visual tests are verified in CI, not regenerated
  silently.
- **Coach:** test smell / flakiness review, pipeline design.

---

## Tier 2 — pick 1–2 for breadth

### 5. Keycloak + Kong edge

JWT/PKCE for the web client + client-credentials for MCP, JWKS validation at the
gateway. High "security fundamentals" signal.

- **You build:** realm setup, PKCE flow, gateway JWT plugin, MCP client-creds.
- **Definition of done:** `/admin` is locked behind a real login; an MCP client
  gets a short-lived token; a forged token is rejected at Kong, not in the app.

### 6. MCP server (Phase 7)

Topical and lands the project's theme fast; comparatively light — a good
"weekend win" once 1–4 are done.

- **You build:** tools over the existing REST/GraphQL/gRPC APIs, auth shim.
- **Definition of done:** an agent can "list products → place an order → show order
  status" via MCP tools with real data, no duplicated business logic.

---

## Skip / defer (low ROI to hand-build now)

- **Redis read-through cache** — easy, but a one-hour caching-fundamentals demo;
  not Staff-accelerating.
- **Full-text search** — contained CRUD-level work.
- **K8s / Helm** — only tackle after Kafka if you want infra depth; it teaches ops,
  not distributed-systems reasoning.

---

## The coaching contract

> **You build, I coach.** Architecture review before you start, spotting pitfalls
> (outbox transaction boundaries, idempotency collisions, span context through
> Kafka), and code review after. opencode won't write the implementation unless
> you're stuck and ask.

**Start here:** Item 1 (real gRPC Charge) — it's the smallest and makes the current
demo fully real. Track progress in the [**Development Tracking**](./dev-tracking.md#todos) page (flip
`StubPaymentClient` to ✅ when done).