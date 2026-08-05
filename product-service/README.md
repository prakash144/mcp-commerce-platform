# Product Service

**Protocol:** REST (Java 21 + Spring Boot 4.1)
**Responsibility:** Product catalog CRUD, search, inventory management

---

## Architecture (Layers)

```
┌─────────────────────────────────────────────────────────────────────┐
│                        API Gateway (Kong)                           │
│                    /api/v1/products/*                               │
└──────────────────────────┬──────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────────┐
│  ProductController                (@RestController)                 │
│  - /api/v1/products                HTTP adapter (thin)              │
│  - /api/v1/products/{id}           input validation via @Valid      │
│  - /api/v1/products/search         pagination via Pageable          │
│  - Error: @ControllerAdvice        RFC 7807 Problem Details         │
└──────────────────────────┬──────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────────┐
│  ProductService                    (business logic layer)           │
│  - createProduct(idempotent)       calls repository + publishes     │
│  - updateProduct                   event to Kafka                   │
│  - deleteProduct                   (Kafka producer — Phase 5)       │
│  - searchProducts                  delegates to repository          │
└──────────────────────────┬──────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────────┐
│  ProductRepository                (Spring Data JPA interface)       │
│  - extends JpaRepository          standard CRUD                     │
│  - findBySku                      custom query                      │
│  - findByNameContainingIgnoreCase  search query                     │
└──────────────────────────┬──────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Product Entity                    (JPA entity, not exposed to API) │
│  - id (UUID)                      database identity                 │
│  - name, description, price       core fields                       │
│  - sku (unique)                   stock-keeping unit                │
│  - stock                          inventory count                   │
│  - created_at, updated_at         audit timestamps                  │
└──────────────────────────┬──────────────────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────────────────┐
│  PostgreSQL (productdb)            database-per-service              │
└──────────────────────────────────────────────────────────────────────┘

    ┌──────────────┐     ┌──────────────┐     ┌──────────────┐
    │   Flyway     │────▶│   Product    │────▶│  DTOs        │
    │  migration   │     │   Entity     │     │  (Request/   │
    │  V1__*.sql   │     │   (JPA)      │     │   Response)  │
    └──────────────┘     └──────┬───────┘     └──────┬───────┘
                               │                    │
                               │    ┌──────────────┐ │
                               └────│  MapStruct   │─┘
                                    │   Mapper     │
                                    └──────────────┘
```

---

## Build Order

Each layer is built top-down (start with data, end with HTTP).

| # | Layer | File | What it does |
|---|---|---|---|
| 1 | Config | `application.yml` | Datasource, JPA, Flyway, server port, OpenAPI |
| 2 | Migration | `V1__create_products_table.sql` | CREATE TABLE with all columns |
| 3 | Entity | `Product.java` | JPA entity mapping |
| 4 | Repository | `ProductRepository.java` | Spring Data JPA interface |
| 5 | DTOs | `ProductRequest.java`, `ProductResponse.java`, `PagedResponse.java` | Request/response shapes |
| 6 | Mapper | `ProductMapper.java` | MapStruct Entity ↔ DTO |
| 7 | Exception handler | `GlobalExceptionHandler.java` | RFC 7807 error responses |
| 8 | Service | `ProductService.java` | Business logic |
| 9 | Controller | `ProductController.java` | HTTP endpoints |
| 10 | Verify | `curl` / OpenAPI | Boot and test |

---

## API Endpoints

| Method | Path | Description |
|---|---|---|
| GET | `/api/v1/products` | List all (paginated: `?page=0&size=20`) |
| GET | `/api/v1/products/{id}` | Get by ID |
| GET | `/api/v1/products/search` | Search by name/sku (`?q=term`) |
| POST | `/api/v1/products` | Create (idempotency key optional) |
| PUT | `/api/v1/products/{id}` | Full update |
| PATCH | `/api/v1/products/{id}` | Partial update |
| DELETE | `/api/v1/products/{id}` | Soft/hard delete (TBD) |

---

## Data Flow: Create Product

```
Client → POST /api/v1/products (JSON body)
          ↓
Kong validates JWT → injects X-User-Id, X-User-Roles
          ↓
ProductController.validateInput(@Valid ProductRequest)
          ↓
ProductService.createProduct()
          ↓ (idempotency check)
ProductRepository.findBySku()
          ↓ (if new)
ProductRepository.save()
          ↓
ProductMapper.toResponse(entity)   ← MapStruct
          ↓
Kafka: ProductCreated (Phase 5)
          ↓
201 Created + ProductResponse JSON
```

---

## Error Shape (RFC 7807 Problem Details)

Every error returns this shape:

```json
{
  "type": "https://api.commerce.com/errors/product-not-found",
  "title": "Product Not Found",
  "status": 404,
  "detail": "Product with id 123e4567-e89b-12d3-a456-426614174000 not found",
  "instance": "/api/v1/products/123e4567-e89b-12d3-a456-426614174000"
}
```

---

## Key Design Decisions

- **UUID primary keys** — not auto-increment. UUIDs are safe to expose in URLs, prevent enumeration attacks, and make data merging possible
- **DTOs never expose entities** — MapStruct maps between them. A field change in the entity never leaks to the API without explicit mapper changes
- **Flyway for migrations** — SQL is checked into version control, applied in order. Never `spring.jpa.hibernate.ddl-auto=update` in production
- **Idempotency-ready** — `POST` accepts an optional `Idempotency-Key` header. If the same key is seen within a window, return the existing result instead of duplicating (implemented in service layer, not controller)
- **OpenAPI auto-generated** — springdoc reads annotations at runtime. No hand-written YAML
