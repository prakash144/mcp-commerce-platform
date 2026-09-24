package service

import "context"

type correlationKey struct{}

// WithCorrelation returns a ctx carrying a correlation id, propagated through
// gRPC metadata inbound (set by the logging interceptor) or Kafka headers
// inbound (set by the compensation consumer) so fact events can be traced
// end-to-end on a single saga.
func WithCorrelation(ctx context.Context, correlationID string) context.Context {
	return context.WithValue(ctx, correlationKey{}, correlationID)
}

// CorrelationFrom extracts the correlation id from ctx, if any.
func CorrelationFrom(ctx context.Context) string {
	v, _ := ctx.Value(correlationKey{}).(string)
	return v
}