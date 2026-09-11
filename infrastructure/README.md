# Infrastructure (`infrastructure/`)

**Status: placeholder — populated in Phases 6 & 9.**

Kubernetes manifests, Prometheus/Grafana/Jaeger dashboards, and production
deployment artifacts for the commerce platform.

Today all runnable infrastructure lives in [`docker/`](../docker/): one Postgres
container plus one container per application service — see
[`docs/infrastructure.md`](../docs/infrastructure.md) for the network topology,
per-service healthchecks, and the deployment strategy. Per-service containerized
deployments come first; `infrastructure/` is reserved for the K8s/scaling and
observability (Phase 6) work.