package middleware

import (
	"context"
	"log/slog"
	"time"

	"github.com/commerce/payment-service/internal/service"
	paymentv1 "github.com/commerce/payment-service/pkg/generated"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"
)

func UnaryLogging(logger *slog.Logger) grpc.UnaryServerInterceptor {
	return func(ctx context.Context, req interface{}, info *grpc.UnaryServerInfo, handler grpc.UnaryHandler) (interface{}, error) {
		// Skip compose health probes — not business traffic.
		if info.FullMethod == "/grpc.health.v1.Health/Check" {
			return handler(ctx, req)
		}

		md, _ := metadata.FromIncomingContext(ctx)
		correlationId := ""

		// gRPC metadata keys are lowercase; grpc-gateway forwards headers as-is.
		if vals := md.Get("correlation-id"); len(vals) > 0 {
			correlationId = vals[0]
		} else if vals := md.Get("x-correlation-id"); len(vals) > 0 {
			correlationId = vals[0]
		}

		if correlationId != "" {
			ctx = service.WithCorrelation(ctx, correlationId)
		}

		start := time.Now()

		attrs := []any{"method", info.FullMethod, "correlationId", correlationId}

		// Enrich Charge calls with request-level context.
		if chargeReq, ok := req.(*paymentv1.ChargeRequest); ok {
			attrs = append(attrs,
				"idempotencyKey", chargeReq.GetIdempotencyKey(),
				"amountMinor", chargeReq.GetAmountMinor(),
				"currency", chargeReq.GetCurrency(),
				"orderId", chargeReq.GetOrderId(),
			)
		}

		resp, err := handler(ctx, req)

		code := codes.Unknown
		if st, ok := status.FromError(err); ok {
			code = st.Code()
		}
		attrs = append(attrs, "code", code.String(), "duration_ms", time.Since(start).Milliseconds())

		if code != codes.OK {
			logger.Warn("grpc.access", attrs...)
		} else {
			logger.Info("grpc.access", attrs...)
		}

		// Return correlation in trailers so the caller can verify end-to-end.
		if correlationId != "" {
			grpc.SetTrailer(ctx, metadata.Pairs("correlation-id", correlationId))
		}

		return resp, err
	}
}

func UnaryRecovery(logger *slog.Logger) grpc.UnaryServerInterceptor {
	return func(ctx context.Context, req interface{}, info *grpc.UnaryServerInfo, handler grpc.UnaryHandler) (resp interface{}, err error) {
		defer func() {
			if r := recover(); r != nil {
				logger.Error("panic recovered",
					"method", info.FullMethod,
					"error", r,
				)
				err = status.Error(codes.Internal, "internal error")
			}
		}()
		return handler(ctx, req)
	}
}
