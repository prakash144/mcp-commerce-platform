package observability

import (
	"context"
	"net/http"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"google.golang.org/grpc"
	"google.golang.org/grpc/status"
)

type Metrics struct {
	rpcTotal    *prometheus.CounterVec
	rpcDuration *prometheus.HistogramVec
}

func NewMetrics() *Metrics {
	m := &Metrics{
		rpcTotal: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "grpc_server_handled_total",
			Help: "Total number of RPCs completed on the server, labelled by method and code.",
		}, []string{"grpc_service", "grpc_method", "grpc_code"}),
		rpcDuration: prometheus.NewHistogramVec(prometheus.HistogramOpts{
			Name:    "grpc_server_handling_seconds",
			Help:    "Server handling time in seconds, bucketed by method.",
			Buckets: prometheus.DefBuckets,
		}, []string{"grpc_service", "grpc_method"}),
	}
	prometheus.MustRegister(m.rpcTotal, m.rpcDuration)
	return m
}

func (m *Metrics) UnaryInterceptor() grpc.UnaryServerInterceptor {
	return func(ctx context.Context, req any, info *grpc.UnaryServerInfo, handler grpc.UnaryHandler) (any, error) {
		start := time.Now()
		resp, err := handler(ctx, req)
		code := status.Code(err).String()
		service, method := splitMethod(info.FullMethod)
		m.rpcTotal.WithLabelValues(service, method, code).Inc()
		m.rpcDuration.WithLabelValues(service, method).Observe(time.Since(start).Seconds())
		return resp, err
	}
}

func (m *Metrics) Handler() http.Handler {
	return promhttp.Handler()
}

func splitMethod(full string) (string, string) {
	for i := len(full) - 1; i >= 0; i-- {
		if full[i] == '/' {
			return full[1:i], full[i+1:]
		}
	}
	if len(full) > 0 && full[0] == '/' {
		return "", full[1:]
	}
	return "", full
}
