# Common (`common/`)

**Status: placeholder.**

Shared contracts and libraries referenced by multiple services, so a contract change
is made once and stays in sync:

- `common/proto/` — shared `.proto` contracts (e.g. `payment.proto`)
- `common/events/` — Kafka event schemas (Avro / JSON Schema) for Phases 5+
- `common/libs/` — shared Java library (error codes, tracing headers, DTOs)

The monorepo keeps all services on the same version of these contracts — see
[`ADR-001`](../docs/adr-001-foundation-decisions.md) for the reasoning.