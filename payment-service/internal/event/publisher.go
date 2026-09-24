package event

import (
	"context"
	"encoding/binary"
	"fmt"
	"log/slog"
	"time"

	"github.com/google/uuid"
	avro "github.com/hamba/avro/v2"
	"github.com/segmentio/kafka-go"
)

// PaymentSucceeded is published after a Charge settles (captured) — best-effort,
// at-least-once. Consumers must dedupe on orderId.
type PaymentSucceeded struct {
	OrderID       string
	PaymentID     string
	IdempotencyKey string
	AmountMinor   int64
	Currency      string
	OccurredAt    string
	CorrelationID string
}

// PaymentFailed is reserved for the PSP adapter path where a Charge exhausts
// retries and settles terminal; nothing in the current service produces it yet.
type PaymentFailed struct {
	OrderID       string
	PaymentID     string
	Retryable     bool
	Attempts      int32
	ErrorCode     string
	OccurredAt    string
	CorrelationID string
}

// PaymentRefunded is published when a payment transitions to fully REFUNDED.
type PaymentRefunded struct {
	OrderID       string
	PaymentID     string
	RefundID      string
	AmountMinor   int64
	Currency      string
	OccurredAt    string
	CorrelationID string
}

// PaymentVoided is published when a payment transitions to VOIDED.
type PaymentVoided struct {
	OrderID       string
	PaymentID     string
	OccurredAt    string
	CorrelationID string
}

// confluent wire format = magic byte 0x00 + big-endian schema id + Avro payload.
const (
	wireMagic     byte   = 0x00
	wireHeaderLen int    = 5
	headerCorrId  string = "correlationId"
)

// Publisher writes payment-fact events to Kafka using the Confluent
// Schema-Registry wire format (binary Avro). Publishing is best-effort
// after commit: the settlement already completed synchronously, and the
// order-service is the transactionally strong side of this choreography.
type Publisher struct {
	writer  *kafka.Writer
	schemas *schemaCache
	log     *slog.Logger
}

func NewPublisher(brokers []string, schemaRegistryURL string, log *slog.Logger) (*Publisher, error) {
	schemas, err := newSchemaCache(schemaRegistryURL)
	if err != nil {
		return nil, err
	}
	w := &kafka.Writer{
		Addr:         kafka.TCP(brokers...),
		Balancer:     &kafka.Hash{}, // orderId key hashes consistently → per-order ordering
		BatchSize:    1,
		RequiredAcks: kafka.RequireAll,
		MaxAttempts:  5,
		WriteTimeout: 10 * time.Second,
	}
	return &Publisher{writer: w, schemas: schemas, log: log}, nil
}

func (p *Publisher) Close() error {
	return p.writer.Close()
}

func (p *Publisher) PaymentSucceeded(ctx context.Context, e PaymentSucceeded) error {
	if e.OccurredAt == "" {
		e.OccurredAt = now()
	}
	return p.write(ctx, TopicPaymentSucceeded, e.OrderID, paymentSucceededFields(e))
}

func paymentSucceededFields(e PaymentSucceeded) map[string]any {
	return map[string]any{
		"orderId":        e.OrderID,
		"paymentId":      e.PaymentID,
		"idempotencyKey": e.IdempotencyKey,
		"amountMinor":    e.AmountMinor,
		"currency":       e.Currency,
		"occurredAt":     e.OccurredAt,
		"correlationId":  cid(e.CorrelationID),
	}
}

func (p *Publisher) PaymentFailed(ctx context.Context, e PaymentFailed) error {
	if e.OccurredAt == "" {
		e.OccurredAt = now()
	}
	return p.write(ctx, TopicPaymentFailed, e.OrderID, paymentFailedFields(e))
}

func paymentFailedFields(e PaymentFailed) map[string]any {
	return map[string]any{
		"orderId":       e.OrderID,
		"paymentId":     e.PaymentID,
		"retryable":     e.Retryable,
		"attempts":      e.Attempts,
		"errorCode":     e.ErrorCode,
		"occurredAt":    e.OccurredAt,
		"correlationId": cid(e.CorrelationID),
	}
}

func (p *Publisher) PaymentRefunded(ctx context.Context, e PaymentRefunded) error {
	if e.OccurredAt == "" {
		e.OccurredAt = now()
	}
	return p.write(ctx, TopicPaymentRefunded, e.OrderID, paymentRefundedFields(e))
}

func paymentRefundedFields(e PaymentRefunded) map[string]any {
	return map[string]any{
		"orderId":       e.OrderID,
		"paymentId":     e.PaymentID,
		"refundId":      e.RefundID,
		"amountMinor":   e.AmountMinor,
		"currency":      e.Currency,
		"occurredAt":    e.OccurredAt,
		"correlationId": cid(e.CorrelationID),
	}
}

func (p *Publisher) PaymentVoided(ctx context.Context, e PaymentVoided) error {
	if e.OccurredAt == "" {
		e.OccurredAt = now()
	}
	return p.write(ctx, TopicPaymentVoided, e.OrderID, paymentVoidedFields(e))
}

func paymentVoidedFields(e PaymentVoided) map[string]any {
	return map[string]any{
		"orderId":       e.OrderID,
		"paymentId":     e.PaymentID,
		"occurredAt":    e.OccurredAt,
		"correlationId": cid(e.CorrelationID),
	}
}

func (p *Publisher) write(ctx context.Context, topic, key string, fields map[string]any) error {
	schema, schemaID, err := p.schemas.Latest(ctx, subjectFor(topic))
	if err != nil {
		return err
	}
	payload, err := avro.Marshal(schema, fields)
	if err != nil {
		return fmt.Errorf("avro encode %s: %w", topic, err)
	}
	value := make([]byte, wireHeaderLen+len(payload))
	value[0] = wireMagic
	binary.BigEndian.PutUint32(value[1:5], uint32(schemaID))
	copy(value[wireHeaderLen:], payload)
	return p.writer.WriteMessages(ctx, kafka.Message{
		Topic:   topic,
		Key:     []byte(key),
		Value:   value,
		Headers: []kafka.Header{{Key: headerCorrId, Value: []byte(fields["correlationId"].(string))}},
	})
}

func cid(value string) string {
	if value == "" {
		return uuid.NewString()
	}
	return value
}

func now() string {
	return time.Now().UTC().Format(time.RFC3339)
}