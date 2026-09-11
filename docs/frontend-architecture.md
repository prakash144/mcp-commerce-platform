# Frontend Architecture — `web/` Storefront

Status: **MVP built** (React + Vite SPA, dev-mode direct calls)
Last updated: 2026-09-11

---

## 1. Purpose

A premium storefront proving the whole platform works end to end:
**browse product catalog (REST) → build a cart → check out (GraphQL) → order
status reflecting a real charge (payment via order-service).**

Data flow philosophy: the UI is a **thin consumer**. Zero business logic lives in
the browser — every transaction goes through the existing services.

## 2. Stack

| Concern | Choice | Why |
|---|---|---|
| Build | Vite 8 + TypeScript 6 | Fast dev, first-class TS |
| UI | React 19 | Ecosystem + test tooling |
| Data fetching / cache | TanStack Query v5 | Server-state dedup, retries, loading state |
| Global state | Zustand + `persist` | Cart survives refresh (localStorage) |
| Routing | React Router v7 | Nested layouts, typed-ish routes |
| Styling | Tailwind CSS v4 + shadcn/ui-style primitives | Design tokens, accessible Radix underpinnings |
| Icons | lucide-react | Consistent, tree-shakeable |
| Fonts | Inter Variable (self-hosted via fontsource) | Deterministic rendering for visual tests |
| E2E / visual | Playwright | See `docs/testing-strategy.md` |

## 3. Folder Layout

```
web/
├── e2e/
│   ├── checkout.spec.ts          # full journey + aria structure (real backend)
│   └── checkout.spec.ts-snapshots/  # committed visual-regression baselines
├── src/
│   ├── api/                      # typed clients — the ONLY networking layer
│   │   ├── types.ts              # Product, Order, CartItem...
│   │   ├── products.ts           # REST /api/v1/products + React Query hooks
│   │   └── orders.ts             # GraphQL /graphql (createOrder, order) + hooks
│   ├── components/
│   │   ├── Layout.tsx            # sticky header, footer, <Outlet/>
│   │   ├── ProductArt.tsx        # deterministic gradient placeholder art
│   │   ├── ProductCard.tsx       # card w/ add-to-cart (stock-aware)
│   │   ├── ProductGrid.tsx       # grid + skeleton + error state
│   │   └── ui/                   # Button, Card, Badge, Input, Skeleton
│   ├── lib/utils.ts              # cn(), formatMoney(), formatDate()
│   ├── pages/                    # one file per route
│   ├── store/cart.ts             # Zustand cart (persisted)
│   ├── App.tsx                   # RouterProvider with routes
│   ├── main.tsx                  # QueryClientProvider + font
│   └── index.css                 # Tailwind import + base theme
```

**Rule:** only `src/api/*` talks to the network. Pages/components consume hooks.

## 4. Pages & Routes

| Route | Page | Source of truth |
|---|---|---|
| `/` | Home — hero + value props + featured 8 | `useProducts` (REST) |
| `/catalog` | Collection — paginated grid (12/page), API sort (`price,asc|desc`), `?q=` search filter | `useProducts(page, size, sort)` |
| `/products/:id` | Detail + breadcrumb, qty stepper, trust row | `useProduct(id)` |
| `/cart` | Line items, qty, totals, persist | `useCart` (local) |
| `/checkout` | Review + summary | cart + `useCreateOrder` (GraphQL) |
| `/orders/:id` | Confirmation + status (CONFIRMED/PENDING) | `useOrder(id)` (GraphQL) |
| `*` | 404 | static |

Search (`?q=`) filters the current page **client-side** today; it becomes a real
server-side full-text search once product-service exposes an endpoint (see TODOs).

## 5. Integration Contracts (verified against the code)

**Product REST** `GET /api/v1/products?page=&size=&sort=price,asc|desc`
→ `PagedResponse<ProductResponse>`
`{ content: [ {id,name,description,price,sku,stock,...} ], page, size, totalElements, totalPages }`.
Money is a JSON **number** (e.g. `29.99`). CORS avoided via Vite dev proxy:
`/api → localhost:8081`, `/graphql → localhost:8082`. Product fetch errors carry the
HTTP status (`ApiError.status`) — a 404 renders a "Product not found" page.

**Order GraphQL** `POST /graphql`
- `createOrder(input: { items: [{productId, quantity}], customerId?, currency = "USD" })`
  → `Order` where `BigDecimal` amounts serialize as **numbers**, `DateTime` as ISO strings.
- `order(id: ID!)` → `Order` (`id, customerId, status, totalAmount, currency, items[...], createdAt`).

**Payment:** not called directly by the browser. `createOrder` charges through
order-service (gRPC → payment-service). This keeps payment internal per the
architecture (only the gateway/auth edge is internet-facing).

## 6. State & Errors

- **Loading:** skeletons everywhere (`ProductGridSkeleton`, page-level skeleton).
- **Errors:** REST/GraphQL errors surface as human messages (`ErrorState`, checkout
  maps `extensions.code` → copy). GraphQL errors thrown with `.code` attached.
- **Edge cases handled:** out-of-stock (disabled add + badge), qty clamped to stock,
  empty cart redirects to catalog, order not found → friendly 404 page.

## 7. Dev-mode routing (why direct calls now)

The storefront talks to services **directly** in dev so we could build on a real,
walkable flow immediately. In production the same `/api` and `/graphql` prefixes will
be served by **Kong**, so the app code changes only in `vite.config.ts` (drop the
proxy), not in the data layer.

## 8. Next (matches root README Todos)

- Real gRPC order→payment client (replaces the stub) — checkout is charged via a
  stub until then.
- Keycloak PKCE login + Kong routes/JWT → switch from dev proxy to gateway.
- Admin dashboard (product/catalog management, order view, refunds via payment
  REST gateway) — 🔵 nice-to-have.