# Testing Strategy — Commerce Platform UI

Status: **MVP in place**
Last updated: 2026-09-11

---

## 1. Guiding principle

> Test the **real system** as early and as often as possible. The UI is verified
> against the **live backend services** (not mocks), so an E2E pass proves genuine
> REST → GraphQL → (gRPC) integration, not just browser logic.

## 2. Tooling

| Layer | Tool | Scope |
|---|---|---|
| E2E journeys | Playwright (Chromium) | Full user journeys against real services |
| Visual regression | Playwright `toHaveScreenshot` | Pixel diff vs committed baselines |
| Accessibility | Playwright `ariaSnapshot()` + axe-ready roles | Structural/ARIA checks (partially automated) |
| API-level | curl / GraphiQL / Swagger | Ad-hoc contract checks |
| Component/unit (future) | Vitest + React Testing Library | Cart logic, form validation |
| Per-service integration | Testcontainers (backend) | Product/order service contracts — separate from UI |

## 3. What is automated vs manual

### Automated (Playwright, `web/e2e/checkout.spec.ts`)
- **Journey:** home → catalog → product → cart → checkout → order confirmed.
- **Visual baselines:** 5 stable pages pinned (`01-home` … `05-checkout`); regenerated
  with `npx playwright test --update-snapshots` only when the change is intentional.
- **Structure:** ARIA snapshot of the home page (banner, nav with live cart count,
  headings, product-card names). The ARIA tree doubles as a readable dump for
  human/agent review.
- **Admin (smoke, pending):** a second spec asserting `/admin` KPIs render and the
  Products/Orders/Payments tables load with live data (no mutations).

### Backend unit tests
- `payment-service`: `go test ./...` — idempotency, state machine, validation, and
  the new `ListPayments` filter/pagination (service level with a fake repo).
- `order-service`: `mvn test` — `OrderServiceTest` covers `getOrders` (all vs by
  status/pagination) and `getOrderStats` aggregation with Mockito.

### Manual (human judgment — do NOT automate these)
- Motion/animation feel, spacing polish, brand consistency.
- Mobile touch ergonomics (eventually automated via device viewports).
- Exploratory edge cases (server down, empty DB, bad id in the URL).
- Visual *quality* judgment (does it look premium?) — automated pixel tests catch
  *regressions*, not *design quality*.

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
3. Boot the two Java services + Postgres, `npm run dev`.
4. `npx playwright test` — full journey against the real stack.
5. Screenshots land in `web/e2e/screenshots/` (gitignored) for human review.

## 5. Known limits / next steps

- **The payment leg is stubbed** until order-service calls payment-service over
  gRPC for real. Until then "charged" = stub SUCCESS. E2E stays green regardless.
- Baselines are machine-specific (`-chromium-darwin` suffix); regenerate after OS
  changes or font swaps.
- Add: mobile viewport project, Vitest for cart math/checkout error mapping,
  axe-core automated scan, and a Webhook/percy-style hosted diff once auth lands.
- Add per-PR CI running `build + lint + playwright` with the services as a test
  fixture (docker compose test profile, Phase 9).