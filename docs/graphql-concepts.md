# GraphQL for This Codebase (Beginner Guide)

Short conceptual notes for reading `order-service` with no prior GraphQL. Product-service is REST; order-service is GraphQL — different tools, same layering pattern.

---

## 1. REST vs GraphQL: why one "Swagger-less" endpoint

| | REST (product-service) | GraphQL (order-service) |
|---|---|---|
| Surfaces | many URLs (verbs + paths) | one endpoint: `POST /graphql` |
| Payload | server decides fields (DTOs) | client picks fields per request |
| Docs/tool | Swagger UI (`/swagger-ui.html`) | GraphiQL (`/graphiql`) |
| Changes | versioned URLs or breaking changes | additive schema evolution, no versions |

That's why there is **no OpenAPI/Swagger** in order-service: there is only one
endpoint to document. GraphiQL reads the schema and offers autocomplete + a docs
sidebar, which is the GraphQL equivalent of Swagger UI.

## 2. Schema-first: the contract file

`order-service/src/main/resources/graphql/schema.graphqls` is the contract — it describes
every type, field, argument, and default. Spring servers it to clients (and GraphiQL)
directly. Three building blocks matter:

```graphql
type Order {            # an object type
  id: ID!               # "!" = non-null; this field can never be null
  status: OrderStatus!  # enum
  items: [OrderItem!]!  # list of non-null OrderItem, list itself non-null
  totalAmount: BigDecimal!
}

input CreateOrderInput { # input = argument bag (like a DTO for queries)
  items: [OrderItemInput!]!
  currency: String = "USD"  # schema-side default
}
```

The rules: **`!` means "null is not allowed here"**, `[X]` means list, `[X!]!`
means non-null list of non-nulls, and `= value` is a default applied by the engine.

## 3. Resolvers: who fills the fields

GraphQL has three "kinds" of resolver, mapped by Spring annotations in
`resolver/`:

| Kind | Annotation | Example |
|---|---|---|
| Root query fields | `@QueryMapping` | `order(id)`, `ordersByCustomer(...)` |
| Root mutation fields | `@MutationMapping` | `createOrder(input)`, `cancelOrder(id)` |
| Any object's field | `@SchemaMapping` | `Order.items` (not on the DTO!) |

The field name = method name (or `field`/`typeName` attribute). A resolver is
only called for fields the *client actually asked for*.

## 4. Non-null bubbling: what that extra error is

`cancelOrder: Order!` means: "I promise a real Order back." When a resolver is
unstable (validation fails, error thrown), GraphQL enforces the promise — the
field with the error becomes null, and it *bubbles up* through every enclosing
non-null field. Result: you see the real error **plus** a `NullValueInNonNullableField`
entry on the timeline. That is correct, spec-mandated behavior, not a bug:
client always gets "a value or a loud, typed failure."

## 5. Custom scalars (BigDecimal, DateTime)

`graphql-java` only ships 5 primitive scalars (`Int`, `Float`, `String`,
`Boolean`, `ID`). It ships **no** `BigDecimal`/`DateTime` in core anymore,
so `config/OrderScalars.java` defines them: a `Coercing` that implements
serialize (Java → wire), parseValue (variable → Java), parseLiteral (literal → Java).

Gotcha (bit me live): defining the `GraphQLScalarType` **bean is not enough** —
Spring GraphQL doesn't auto-register scalar beans. You must also register them
in a `RuntimeWiringConfigurer` bean (`GraphQLConfig.orderScalarWiringConfigurer`).

## 6. The N+1 problem and DataLoader

Classic REST-style bug: if each `Order` fetched its items by running its own
query, a response with N orders issues N separate queries (`N+1`).

```sql
-- N orders → without DataLoader:            with DataLoader:
select ... where order_id = $o1            select ... where order_id in ($o1,$o2,...)
select ... where order_id = $o2            -- ONE query for all orders
...
```

Fix: `dataloader/OrderItemDataLoaderConfig.java` registers a **batch loader**.
Every `loader.load(orderId)` returns a future; the engine collects all of them,
then calls the batch function once with every id, which runs a single
`WHERE order_id IN (...)` and splits the results back per order.

Verified live (SQL log): 2 orders → one `IN (?,?)`.

## 7. Errors: extensions vs HTTP codes

GraphQL responses always have `data` + `errors` arrays; there is no HTTP-status
per field. To keep typed errors (the "404 vs 400" feel of REST), this service
uses `extensions.code`:

```json
{ "errors": [{
    "message": "Order with id ... not found",
    "extensions": { "code": "ORDER_NOT_FOUND", "classification": "NOT_FOUND" }
}], "data": { "order": null } }
```

`OrderGraphQLExceptionHandler` (a `@ControllerAdvice`) maps exceptions:
`OrderNotFoundException` → `NOT_FOUND`, `OrderValidationException` → `BAD_REQUEST`
(+ code + details), anything else → `INTERNAL_ERROR` (logged).

## 8. Defaults & args: schema-side vs Java-side

Defaults live in the **schema** (`first: Int = 20`). graphql-java applies them
before the resolver runs, so the Java method still receives `20` even if the
client omitted it. There is no `@Argument(defaultValue=...)` in Spring GraphQL —
your earlier instinct is right, avoid it.

## 9. ID "string" → UUID

`ID!` is an opaque string on the wire. When a resolver declares
`@Argument UUID id`, Spring's argument binder routes the coerced value through
its `ConversionService` (`SimpleTypeConverter`), whose registered
`StringToUUIDConverter` does the conversion. So `ID!` ↔ `UUID` just works.

## 10. Identity across services

Order rows are owned by order-service (`orderdb`); product facts (name/price)
are owned by product-service (`productdb`). order-service calls product-service
over REST at order creation and **snapshots** the values into `order_items`
(price + name). That keeps order history stable even if the product later
changes price or is renamed. Distributed identity (`X-User-Id`/roles from Kong)
reaches the service via request headers, resolved in
`config/CustomerContext.java` with a local-dev fallback (`default-customer-id`).

---

### Gotchas that bit us (keep these handy)

- Custom scalar beans need a `RuntimeWiringConfigurer` to be wired — beans alone are ignored.
- Boot 4.1 has **no** auto-configured `RestClient.Builder` bean — build clients with `RestClient.builder()` directly.
- `@Argument` has no `defaultValue` attribute.
- Concurrent writes from virtual threads require concurrent maps (`ConcurrentHashMap`).
- A boolean/primitive typed to a nullable schema arg (`int first` vs `Int = 20`) makes the schema inspector complain cosmetically; harmless.
- `Order.items` must go through the DataLoader, never a per-order repository call, or you reintroduce N+1.