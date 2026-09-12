# MCP Server (`mcp-server/`)

**Status: placeholder — Phase 7.**

Python MCP server that will expose the existing product (REST), order (GraphQL) and
payment (gRPC) services to AI agents as MCP tools — an **adapter only, no business
logic**. AI clients (Claude, ChatGPT) will talk to this server over MCP, and it wraps
the same APIs the web storefront already uses.

Structure already scaffolded:

```
src/mcp_server/
├── auth/       # MCP client credential flow (Keycloak, later)
├── clients/    # REST / GraphQL / gRPC clients for the three services
├── schemas/    # Tool input/output schemas
└── tools/      # Tool definitions (thin — delegate to clients)
```

Plans: [`docs/plan.md §2.4`](../docs/plan.md), architecture at
[`docs/architect.md §3`](../docs/architect.md). Nothing here runs yet — see the root
[`README Roadmap`](../README.md#roadmap--phases) and the `AI` todos.