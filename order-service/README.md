# Order Service

**Protocol:** GraphQL (Java 21 + Spring Boot 4.1)
**Responsibility:** Order creation, cancellation, and querying — validates products (via product-service REST), computes totals, confirms via payment, snapshots prices

**Learn first:** if you are new to GraphQL, read [`../docs/graphql-concepts.md`](../docs/graphql-concepts.md) before this README.

---

## Architecture (Layers)

```
┌─────────────────────────────────────────────────────────────────────┐
│                        API Gateway (Kong)                           │
│                     POST /graphql  +  /graphiql                     │
│                    X-User-Id, X-User-Roles headers                  │
└──────────────────────────┬──────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Resolvers (@Controller)             GraphQL adapter (thin)         │
│  - OrderQueryResolver                @QueryMapping                  │
│      order(id)                       @SchemaMapping: Order.items    │
│      ordersByCustomer(...)           uses DataLoader (batched,      │
│  - OrderMutationResolver             kills N+1 — see below)         │
│      createOrder(input) @MutationMapping                            │
│      cancelOrder(id)                                                │
└──────────────────────────┬──────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────────┐
│  OrderService                        (business logic)               │
│  - createOrder: validate → fetch products → persist PENDING →       │
│                  payment stub → CONFIRMED                           │
│  - cancelOrder: status guard + flip to CANCELLED                    │
│  - getOrder / getOrdersByCustomer   paged queries                   │
└───────────────┬─────────────────────┬───────────────────────────────┘
                │                     │
                ▼                     ▼
┌─────────────────────────┐  ┌────────────────────────────────────────┐
│  Spring Data JPA        │  │  Clients                               │
│  OrderRepository        │  │  - ProductClient / RestProductClient   │
│  OrderItemRepository    │  │     → GET :8081/api/v1/products/{id}   │
│                         │  │     (parallel, fail-fast)              │
│  Entities: Order,       │  │  - PaymentClient / GrpcPaymentClient   │
│  OrderItem (snapshot)   │  │     → gRPC payment-service :50051      │
└─────────────┬───────────┘  └────────────────────────────────────────┘
              │
              ▼
┌─────────────────────────────────────────────────────────────────────┐
│  PostgreSQL (orderdb)    Flyway V1 + V2 (retry/idempotency cols)     │
└─────────────────────────────────────────────────────────────────────┘

  GraphQL flow per query:
      Query/Mutation  →  Resolver  →  Service  →  Repository  →  DB
                          │  Order.items
                          ▼
              DataLoader batches all requested order ids
              → ONE  SELECT ... WHERE order_id IN (?,...)
```

---

## Build Order

Top-down, matching the product-service convention (data first, GraphQL last).

| # | Layer | File (under `src/main/java/com/commerce/order/`) | What it does |
|---|---|---|---|
| 1 | Config | `resources/application.yaml` | Port 8082, `orderdb` datasource, Flyway, GraphiQL, product-service URL, retry-job tuning (`commerce.order.retry.*`) |
| 2 | Migration | `resources/db/migration/V1__create_orders_tables.sql`, `V2__add_charge_idempotency.sql` | `orders` + `order_items`; V2 adds `idempotency_key` (unique), `payment_id`, `charge_attempts`, `last_charge_error`, `next_retry_at` |
| 3 | Entity | `entity/Order.java`, `OrderItem.java`, `OrderStatus.java` | JPA entities; `status` enum; `totalAmount` + per-item `lineTotal` computed |
| 4 | Repository | `repository/OrderRepository.java`, `OrderItemRepository.java` | `findByCustomerId(String, Pageable)`, `findByOrderIdIn(Set<UUID>)` |
| 5 | DTOs | `dto/CreateOrderInput.java`, `OrderItemInput.java`, `OrderOutput.java`, `OrderItemOutput.java`, `OrderPageOutput.java` | GraphQL request/response shapes |
| 6 | Scalar | `config/OrderScalars.java` | Hand-rolled `BigDecimal` / `DateTime` coercing (graphql-java 25 removed them from core) |
| 7 | Wiring | `config/GraphQLConfig.java` | `RuntimeWiringConfigurer` registers the scalars; depth 10 / complexity 50 guards |
| 8 | Mapper | `mapper/OrderMapper.java` | MapStruct Entity ↔ DTO (`toOutput`, `toItemOutput`) |
| 9 | Errors | `exception/OrderNotFoundException.java`, `OrderValidationException.java`, `OrderGraphQLExceptionHandler.java` | typed codes via `extensions.code` |
| 10 | Clients | `client/ProductClient.java`, `RestProductClient.java`, `ClientConfig.java`, `PaymentClient.java`, `GrpcPaymentClient.java`, `CorrelationInterceptor.java` | product REST fetch (virtual threads), gRPC payment with retry+breaker (`ResilienceConfig`) |
| 11 | Job | `job/PendingOrderRetryJob.java` | `@Scheduled` poll of PENDING orders past `next_retry_at`, reusing the persisted idempotency key |
| 11 | Service | `service/OrderService.java` | Business logic — create/cancel/query |
| 12 | DataLoader | `dataloader/OrderItemDataLoaderConfig.java` | Batched `items` loading for Order |
| 13 | Resolvers | `resolver/OrderQueryResolver.java`, `OrderMutationResolver.java` | GraphQL entry points |
| 14 | Verify | GraphiQL + curl | Boot and test |

---

## GraphQL Operations

| Operation | Signature | Description |
|---|---|---|
| `order` | `order(id: ID!): Order` | Get one order (nullable → `ORDER_NOT_FOUND` if absent) |
| `ordersByCustomer` | `ordersByCustomer(customerId: String, first: Int = 20, offset: Int = 0): OrderPage!` | Paged list; `customerId` falls back to header → default |
| `createOrder` | `createOrder(input: CreateOrderInput!): Order!` | Validate → fetch products → PENDING → payment → CONFIRMED |
| `cancelOrder` | `cancelOrder(id: ID!): Order!` | Set CANCELLED (idempotency guard) |

**Scalars:** `BigDecimal` (money), `DateTime` (ISO-8601). **Enums:** `OrderStatus { PENDING CONFIRMED CANCELLED FAILED }`.

---

## Internal Flow: createOrder (exactly-once)

```
Client → POST /graphql  { mutation createOrder(input: {items: [...]}) }
          ↓  (gateway header X-User-Id: customer-1)
OrderMutationResolver.createOrder(@Argument CreateOrderInput)
          ↓
OrderService.createOrder()
  │  ① validateAndNormalize          typed errors: EMPTY_ORDER, INVALID_ITEM,
  │                                  INVALID_QUANTITY, INVALID_CURRENCY
  ├─▶ ProductClient.fetchByIds(...)  → product-service :8081 (parallel virtual threads)
  │        x→ PRODUCT_VALIDATION_FAILED (fail-fast, typed) if product-service is down
  │  ② build Order PENDING          + random idempotencyKey
  │  ③ [TX-1: COMMIT] save PENDING + idempotency_key   ← durable BEFORE any payment call
  │  ④ chargeAndSettle(orderId)
  │        attempt = chargeAttempts + 1
  │        charge → GrpcPaymentClient( persisted idempotencyKey )
  │                  retry ×3 ▸ breaker ▸ 3s deadline per attempt
  │        outcome recorded in [TX-2: COMMIT]:
  │          SUCCESS → CONFIRMED + payment_id
  │          FAILED  → FAILED (declined, terminal)
  │          throws  → PENDING + attempt++, last_charge_error,
  │                    next_retry_at = now + exp backoff  (rethrow to client)
  │                    attempts ≥ max → FAILED (terminal)
  └─▶ OrderMapper.toOutput(saved)    → Order JSON (items resolved later, see below)
```

Why the tx split matters: TX-1 commits the retry-intent before the charge call; TX-2
commits the outcome after it. The payment RPC is never inside a DB transaction, so a
timeout/breaker trip can't roll back the durable PENDING+key record — the same key is
reused on every attempt (payment-service dedupes by key ⇒ exactly-once, no double charge).

## Internal Flow: PENDING-order automated retry

```
@Scheduled(15s) PendingOrderRetryJob
  → findPendingDue(now) : PENDING ∧ attempts>0 ∧ next_retry_at ≤ now  (batch 20)
  → orderService.retryPending(id) → chargeAndSettle (same path as ④)
```

## Internal Flow: reading `Order.items` (the N+1 fix)

```
Query  { ordersByCustomer { orders { items { ... } } } }
          │   N orders returned, each requesting `items`
          ▼
DataLoader<UUID, List<OrderItemOutput>>.load(orderId)
   for EVERY order in the response      ← parallel futures, coalesced
          │
          ▼  (one batch lambda, fires once)
SELECT ... FROM order_items WHERE order_id IN (?,?,...)   ← 1 query, not N
          ▼
Mapped back per order, empty list [] for orders with no items
```

Verified against the live database — a request returning 2 orders produced exactly
one `IN (?,?)` statement for items (full SQL shown in the log with
`-Dspring-boot.run.jvmArguments="-Dspring.jpa.show-sql=true"`).

---

## Error Shape

GraphQL has a single error envelope; typed codes ride in `extensions`. Examples seen live:

```json
{ "errors": [{
    "message": "Order with id 00000000-0000-0000-0000-000000000000 not found",
    "extensions": { "code": "ORDER_NOT_FOUND", "classification": "NOT_FOUND" }
}], "data": { "order": null } }
```

| code | trigger |
|---|---|
| `ORDER_NOT_FOUND` | `order(id)`/`cancelOrder(id)` — unknown id |
| `EMPTY_ORDER`, `INVALID_ITEM`, `INVALID_QUANTITY`, `INVALID_CURRENCY` | `createOrder` input validation |
| `PRODUCT_VALIDATION_FAILED` | product-service unreachable or product missing |
| `ORDER_ALREADY_CANCELLED` | cancelling a cancelled order |
| `ORDER_NOT_CANCELLABLE` | cancelling a `CONFIRMED` / `FAILED` order |
| `PAYMENT_UNAVAILABLE` | breaker open or retries exhausted — order stays PENDING and is retried by the job |
| `PAYMENT_REJECTED` | permanent rejection (e.g. invalid argument) from payment-service |
| `INTERNAL_ERROR` | catch-all (logged now) |

Note: `createOrder: Order!` (non-null) → on error, graphql-java also bubbles a
`NullValueInNonNullableField` entry. That is the spec's behavior: the null-poisoning
rises through non-null fields. Correct, just noisy — leave it.

---

## Key Design Decisions

- **GraphQL, not REST** — one endpoint, clients pick fields (no over/under-fetching, no versioning). Swagger/OpenAPI therefore does not apply (it documents REST endpoints); GraphiQL is the equivalent interactive tool.
- **Schema-first** — `resources/graphql/schema.graphqls` is the contract. Resolvers just fill the fields; the engine validates queries against it.
- **Field resolver + DataLoader for `Order.items`** — prevents the classic N+1. Items are never stored on `OrderOutput`; they are fetched in one batched query (see above).
- **Custom scalars via `RuntimeWiringConfigurer`** — defining `GraphQLScalarType` beans is NOT enough (Spring GraphQL does not auto-register them). They must be wired in `GraphQLConfig.orderScalarWiringConfigurer`.
- **Price/name snapshots** — item rows carry `product_name` + `unit_price` captured at order time; later product changes don't rewrite history. `lineTotal` & `totalAmount` are computed, not persisted.
- **Persisted idempotency + exactly-once charge** — the order's `idempotency_key` is written in a committed tx *before* the payment call and reused on every retry; payment-service dedupes by that key, so a charge can never apply twice even if an attempt is ambiguous.
- **Duel transaction boundaries around the payment RPC** — TX-1 commits retry intent (PENDING+key), TX-2 commits the outcome; the RPC itself has no outer DB tx, so failures can't roll persistence back. Attempt outcomes are committed even when the charge throws.
- **Automated PENDING retry** — `PendingOrderRetryJob` polls `status=PENDING ∧ next_retry_at≤now` with exponential backoff (`initial-backoff`, `max-backoff`) and a `max-attempts` cap → terminal `FAILED`. Tune via `commerce.order.retry.*`.
- **ID scalar → UUID** — GraphQL `ID!` coerces to String; Spring's `GraphQlArgumentBinder` + `ConversionService` converts it to a `UUID` param automatically.
- **Boot 4.1 fact: no auto-configured `RestClient.Builder` bean** — the old REST `RestClientAutoConfiguration` is gone from the modularized starter. `ClientConfig` builds the client directly (`RestClient.builder()`).
- **`@Argument` has no `defaultValue` in Spring GraphQL 2.0.4** — schema-side defaults (`first: Int = 20`) are applied by graphql-java before the resolver runs.
- **Concurrent fetches need concurrent-safe maps** — `RestProductClient` initially wrote results into a `LinkedHashMap` from virtual threads; racing `put`s lost entries (500). Uses `ConcurrentHashMap`.
- **Flyway + `ddl-auto: validate`** — schema comes only from versioned SQL.

---

## Running & Testing

### Prerequisites

```bash
# Postgres (once)
docker compose -f docker/docker-compose.yml up -d postgres
docker compose -f docker/docker-compose.yml ps   # wait for "healthy"
```

### Boot order-service

```bash
cd order-service
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  mvn org.springframework.boot:spring-boot-maven-plugin:4.1.0:run
# or simply:  mvn spring-boot:run   (needs your default JDK to be 21)
```

App starts on `http://localhost:8082`.
Optional SQL-log recipe for the boot: add
`-Dspring-boot.run.jvmArguments="-Dspring.jpa.show-sql=true"` to the `mvn` command.

### Boot product-service (needed for createOrder)

```bash
cd product-service
./mvnw spring-boot:run      # :8081
```

Seed products, e.g.:

```bash
curl -X POST http://localhost:8081/api/v1/products -H "Content-Type: application/json" \
  -d '{"name":"Wireless Keyboard","description":"Bluetooth keyboard","price":49.99,"sku":"KB-001","stock":100}'
```

### Test through GraphiQL (recommended)

Open **`http://localhost:8082/graphiql`** — built-in editor with schema docs and autocomplete (GraphQL's version of Swagger UI).

```graphql
mutation {
  createOrder(input: {
    customerId: "customer-1"
    items: [{ productId: "<product-uuid>", quantity: 2 }]
  }) {
    id status totalAmount items { productName quantity lineTotal }
  }
}
```

```graphql
query {
  ordersByCustomer(customerId: "customer-1", first: 10, offset: 0) {
    totalCount orders { id status totalAmount items { productId quantity } }
  }
}
```

### Test via curl

```bash
curl http://localhost:8082/graphql -H 'Content-Type: application/json' \
  -H 'X-User-Id: customer-1' \
  -d '{"query":"query { ordersByCustomer(customerId: \"customer-1\") { totalCount orders { id status } } }"}'
```

### IntelliJ Database (validate tables)

1. **View → Tool Windows → Database** → **+** → **Data Source → PostgreSQL**
2. Host `localhost`, Port `5432`, Database `orderdb`, User `commerce`, Password `commerce_pass`
3. Verify:
   ```sql
   SELECT * FROM orders;
   SELECT * FROM order_items;
   SELECT * FROM flyway_schema_history;   -- success = true
   ```

### Stop

```bash
docker compose -f docker/docker-compose.yml down -v    # wipe data
```

---

## TODO

- [ ] Integration tests: Testcontainers (Postgres + Flyway migration, retry job against real DB)
- [ ] Product-client resilient variant: retry/backoff instead of fail-fast
- [ ] `next_retry_at` ordering page-wise retry fanout when multiple instances run the job (SKIP LOCKED)

---

## Pending (Phase 3 incomplete)

| Item | Status | Notes |
|---|---|---|
| GraphQL schema (`order`, `ordersByCustomer`, `createOrder`, `cancelOrder`) | ✅ Done | `schema.graphqls`, schema-first |
| Custom scalars `BigDecimal` / `DateTime` | ✅ Done | Hand-rolled coercing (`OrderScalars`) + `RuntimeWiringConfigurer` |
| Resolvers (`@QueryMapping`, `@MutationMapping`, `@SchemaMapping`) | ✅ Done | `OrderQueryResolver`, `OrderMutationResolver` |
| DataLoader batching for `Order.items` (N+1 fix) | ✅ Done | One `WHERE order_id IN (...)` per query — verified in SQL log |
| Typed errors via `extensions.code` | ✅ Done | `OrderGraphQLExceptionHandler` |
| product-service REST integration + price snapshots | ✅ Done | Parallel virtual-thread fetch (`RestProductClient`) |
| GraphiQL | ✅ Done | `http://localhost:8082/graphiql` |
| Unit tests | ✅ Done | OrderService, GrpcPaymentClient, ResilienceConfig, PendingOrderRetryJob |
| Integration tests | ❌ Pending | Testcontainers (Postgres + Flyway) |
| Real payment gRPC client | ✅ Done | `GrpcPaymentClient` (retry×3 + breaker + 3s deadline) |
| Product-client resilience (retry/backoff) | ❌ Pending | Fail-fast today by design |