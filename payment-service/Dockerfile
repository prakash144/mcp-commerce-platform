# Multi-stage: Go build (static binary) → Alpine runtime with ca-certificates,
# non-root user, and grpc-health-probe for container healthchecks.

FROM golang:1.26-alpine AS build
RUN apk add --no-cache ca-certificates curl
WORKDIR /workspace
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/server ./cmd/server

ARG GRPC_HEALTH_PROBE_VERSION=v0.4.28
RUN set -eux; \
    case "$(uname -m)" in aarch64|arm64) ARCH=arm64;; x86_64|amd64) ARCH=amd64;; esac; \
    curl -fsSL --retry 3 -o /out/grpc_health_probe \
      "https://github.com/grpc-ecosystem/grpc-health-probe/releases/download/${GRPC_HEALTH_PROBE_VERSION}/grpc_health_probe-linux-${ARCH}" \
    && chmod +x /out/grpc_health_probe

FROM alpine:3.20
RUN apk add --no-cache ca-certificates && addgroup -S app && adduser -S app -G app
USER app
WORKDIR /home/app
COPY --from=build /out/server /usr/local/bin/server
COPY --from=build /out/grpc_health_probe /usr/local/bin/grpc_health_probe
EXPOSE 50051 8090
ENTRYPOINT ["/usr/local/bin/server"]