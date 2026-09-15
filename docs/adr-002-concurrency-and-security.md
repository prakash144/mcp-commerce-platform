# ADR-002: Concurrency & Consistency, and Security — Scope, Design, and Test Plans

**Status:** Accepted  
**Date:** 2026-09-15  
**Driver:** Staff-level engineering concern: exactly-once behavior under concurrency, and security as a first-class (especially once an AI/MCP layer is exposed to the network)

---

## Context

Two cross-cutting concerns remain after the M4 idempotency work:

**Concurrency** — the M4 retry job fixed *duplicate charges* via a persisted idempotency
key + payment-service dedupe, but the guarantee currently rests on **single-instance
assumptions**:

- `OrderService.chargeAndSettle` reads the order, calls the payment RPC, then commits
  the outcome — two actors (double-submit, retry job + manual retry, or a second
  instance) can both pass the `PENDING` read and both commit.
- `OrderRepository.findPendingDue` has no `FOR UPDATE SKIP LOCKED` → two instances can
  pick the same PENDING order.
- `createOrder` has no client-supplied idempotency key → retrying a create mutation
  produces two orders (a *different* bug than double-charge).
- No optimistic locking (`@Version`), no bulkhead on the payment RPC, and no server-side
  pagination cap.

**Security** — the current posture is **no auth anywhere**: `kong-config.yml` is empty
(`services: []`, `plugins: []`), no Keycloak in compose, and services trust whatever
`X-User-Id` header arrives. Today any caller can set `X-User-Id` and read/cancel someone
else's orders. The MCP/AI layer (Phase 7–8) adds a brand-new attack surface: LLM-facing
tools are reachable by prompt injection, and machine-to-machine tokens need scoping.

Ask: does concurrency need a **new phase**? And how do we design + efficiently test both
concurrency and security?

---

## Decision 1: Concurrency is a hardening milestone, not a new phase

**Chosen: fold concurrency into the existing plan as Phase 5.5 (Concurrency & Consistency).**

Reasoning: it changes **guarantees**, not **protocols** — no new transport, schema,
or service. REST/GraphQL/gRPC shapes are untouched, so it deserves a focused DoD inside
the current plan rather than its own top-level milestone (which would imply new surfaces).

### Race analysis (what Phase 5.5 must fix)

| Surface | Today | Fix |
|---|---|---|
| `chargeAndSettle` (OrderService) | read → RPC → commit; two actors can both settle | **Atomic transition guard** in TX-2: `UPDATE orders SET status=…, … WHERE id=? AND status='PENDING'` — only the winning actor's row is updated; loser sees 0 rows and aborts. The row-change *is* the lock. |
| `Order` entity | no `@Version` | Optimistic lock as belt-and-braces; lost updates surface `OptimisticLockException` at a testable point |
| `findPendingDue` | plain read | `FOR UPDATE SKIP LOCKED` → disjoint work sets across instances |
| `createOrder` | no input key | Client `idempotencyKey` in `CreateOrderInput`, deduped by unique index; repeat returns the existing order instead of creating a second |
| payment RPC | unbounded concurrency | Resilience4j `Bulkhead` + a dedicated gRPC executor/connection-pool boundary |
| `getOrders(first)` | `first` passthrough | Server-side cap (`first ≤ 100`) |

### Definition of Done

- [ ] Exactly-one settlement per order under N concurrent actors (guard proven by race tests)
- [ ] `findPendingDue` returns disjoint sets from two instances
- [ ] Identical `createOrder` idempotency keys yield one order
- [ ] Bulkhead configured + observed (payment latency no longer starves order-service)
- [ ] `first ≤ 100` enforced → GraphQL rejects abuse
- [ ] `OptimisticLockException` handled (surface as typed GraphQL error, not 500)

### Concurrency test plan (efficient = deterministic first)

1. **Deterministic race tests (unit/component, fast, no flakes)** — inject a
   `CountDownLatch` seam into `chargeAndSettle` between read and TX-2 write; fire two
   threads; assert exactly one TX-2 commit, one charge RPC with the same key, final
   state `CONFIRMED`, `charge_attempts=1`. With `@Version`: two parallel saves → one
   `OptimisticLockException`.
2. **DB-level integration (Testcontainers Postgres)** — two instances both query
   `findPendingDue` → disjoint sets (SKIP LOCKED); 20 concurrent `createOrder` with the
   same key → 1 order, 19 returned; duplicate key rejected by unique index (DB is the
   final arbiter).
3. **Stress profile (opt-in `-Dgroups=stress`, out of fast CI)** — 100-thread bursts +
   flaky payment sim (UNAVAILABLE ~20%); assert reconciliation: **metrics are the test
   oracle** — `sum(commerce_charge_attempts_total) == commerce_orders_total + exhausted`.
4. **Chaos soak** — kill payment-service mid-soak; assert the guard + job settle every
   order exactly once, no duplicates.

Efficiency: (1)+(2) are deterministic, sub-second-ish with Testcontainers, and can gate
every PR; (3)+(4) run on a nightly/stress schedule only.

---

## Decision 2: Security gets a dedicated milestone — Phase 6.5, threat-model-first

**Chosen: Phase 6.5 (Security) as its own milestone, because most of it is software we
don't have yet** (authn/authz/edge controls) — not hardening of existing code. Sequence:
**threat model → authn → authz → edge controls → supply chain → MCP guardrails.**

### Threat model (short table — drives every control)

| Asset | Attack | Blast radius | Primary control |
|---|---|---|---|
| Orders (PII-adjacent) | Broken access control via spoofed `X-User-Id` | Read/cancel any order | Gateway **strips** inbound `X-User-Id`, overwrites from verified JWT claims + service-side ownership check |
| Order/Product APIs | No auth → unbounded abuse | Free load, data scrape | JWT + rate limiting at gateway (Redis token bucket) |
| GraphQL | Deep/nested abusive queries | Resource exhaustion | Depth/complexity limits (already present) + `first≤100` clamp at gateway too |
| payment-service | Reachability from outside | Direct refund/charge abuse | Private network only — never on a `ports:` mapping |
| CI/CD + images | Vulnerable/branded deps | Supply-chain compromise | SBOM + OWASP dependency-check + `govulncheck` + `npm audit` + Trivy scan |
| MCP tools (AI layer) | **Prompt injection → tool misuse / exfiltration** | Unauthorized orders/cancels | Least-privilege client-credentials token, read-only vs destructive tool split + human-in-the-loop, allow-list at gateway, per-request budget + rate limits for agents, audit logs to Loki |
| Secrets | Committed creds | Total compromise | `gitleaks` incl. `git log` history; env-var-only config (already enforced) |

### Definition of Done

- [ ] Keycloak + Kong JWT (per ADR-001): user PKCE flow + MCP client-credentials flow
- [ ] Gateway strips/replaces spoofable headers; **authz tests block cross-customer access**
- [ ] Rate limiting (Redis token bucket) + pagination cap enforced at the gateway
- [ ] payment-service not externally reachable
- [ ] SAST (Semgrep/CodeQL) + deps scanning + secrets scan wired into PR CI
- [ ] MCP server: scoped tokens, read-only vs destructive split, HITL on `refund`/`cancel`, audit trail

### Security test plan (efficient = shift-left negative tests in CI, DAST nightly)

| Layer | Technique | Every PR | Nightly |
|---|---|---|---|
| Authn/z | Negative tests: no token→401, wrong role→403, spoofed `X-User-Id` stripped, user A can't read user B | ✅ | ✅ |
| SAST | Semgrep/CodeQL (Java, Go, TS) | ✅ | ✅ |
| Deps/supply chain | OWASP dependency-check, `govulncheck`, `npm audit`, Trivy (cached) | ✅ | ✅ |
| Secrets | `gitleaks` + history scan | ✅ | ✅ |
| DAST | OWASP ZAP baseline + `graphql-cop`/InQL fuzzing on the running compose stack | — | ✅ |
| MCP guardrails | Adversarial prompt-injection harness, tool allow-list, token-scope verify | when Phase 7 lands | ✅ |

---

## Decisions Log

| # | Decision | Choice | Reasoning |
|---|---|---|---|
| 1 | Concurrency scope | Hardening milestone **5.5** in existing plan | Changes guarantees, not protocols |
| 2 | Settlement race fix | Single `UPDATE … WHERE status='PENDING'` guard (+ `@Version`) | The row-change is the lock — no external lock service |
| 3 | Retry-job multi-instance | `FOR UPDATE SKIP LOCKED` in `findPendingDue` | Disjoint work sets, no duplicate job picks |
| 4 | Create-order dedupe | Client `idempotencyKey` + unique index | Fixes double-create (different bug from double-charge) |
| 5 | Security scope | Dedicated **6.5** milestone, threat-model-first | Mostly new software (authn/authz), not hardening |
| 6 | Edge authn | Keycloak + Kong JWT (confirmed from ADR-001) | Stateless JWKS validation, no per-req Keycloak call |
| 7 | Service authz | Gateway overwrites header claims + service-side ownership check | Defense-in-depth; spoofing dead on arrival |
| 8 | AI-era security | Least-privilege MCP tokens + destructive-tool HITL + audit trail | Prompt-injection is the #1 AI surface |
| 9 | Test strategy | Deterministic race tests in PR CI, stress + DAST nightly, metrics as test oracle | Fast feedback without flaky tests |

---

## Status of Phases

- Phase 5.5 (Concurrency & Consistency): **Planned** — see DoD above
- Phase 6.5 (Security): **Planned** — see DoD above
- Existing phases 0–6 continue per `plan.md` (M4 idempotency + PENDING retry are complete)