package event

import (
	"context"
	"encoding/binary"
	"fmt"
	"log/slog"
	"time"

	avro "github.com/hamba/avro/v2"
	"github.com/segmentio/kafka-go"
)

// CancellationMsg is a decoded orders.cancelled record — the saga compensation
// trigger for payment-service.
type CancellationMsg struct {
	OrderID       string
	Reason        string
	CorrelationID string
}

// CancellationHandler reacts to one OrderCancelled (idempotent by design).
type CancellationHandler func(ctx context.Context, m CancellationMsg) error

// CancellationConsumer reads orders.cancelled in group "payment-compensation",
// decodes the Avro payload (Confluent wire format, schema resolved from the
// registry by ID), and invokes the handler. On handler failure it retries a
// few times, then parks the record on <topic>.payment-compensation.DLQ so the
// offset can be committed and no message is lost.
type CancellationConsumer struct {
	reader  *kafka.Reader
	dlq     *kafka.Writer
	schemas *schemaCache
	handle  CancellationHandler
	log     *slog.Logger
}

func NewCancellationConsumer(brokers []string, schemaRegistryURL string, handle CancellationHandler, log *slog.Logger) (*CancellationConsumer, error) {
	schemas, err := newSchemaCache(schemaRegistryURL)
	if err != nil {
		return nil, err
	}
	reader := kafka.NewReader(kafka.ReaderConfig{
		Brokers: brokers,
		GroupID: CompensationGroupID,
		Topic:   TopicOrdersCancelled,
		MinBytes: 1,
		MaxBytes: 10 << 20,
		MaxWait:  100 * time.Millisecond,
	})
	dlq := &kafka.Writer{
		Addr:         kafka.TCP(brokers...),
		Balancer:     &kafka.Hash{},
		BatchSize:    1,
		RequiredAcks: kafka.RequireAll,
		MaxAttempts:  5,
		WriteTimeout: 10 * time.Second,
	}
	return &CancellationConsumer{reader: reader, dlq: dlq, schemas: schemas, handle: handle, log: log}, nil
}

func (c *CancellationConsumer) Close() error {
	if err := c.reader.Close(); err != nil {
		return err
	}
	return c.dlq.Close()
}

// Run consumes until ctx is cancelled.
func (c *CancellationConsumer) Run(ctx context.Context) error {
	for {
		m, err := c.reader.ReadMessage(ctx)
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			c.log.Error("compensation read error", "error", err)
			continue
		}
		msg, err := c.decode(ctx, m)
		if err != nil {
			c.log.Error("compensation decode failure -> DLQ", "topic", m.Topic, "partition", m.Partition, "offset", m.Offset, "error", err)
			c.toDLQ(ctx, m)
			continue
		}
		if err := retry(3, 250*time.Millisecond, func() error { return c.handle(ctx, msg) }); err != nil {
			c.log.Error("compensation handler exhausted retries -> DLQ", "orderId", msg.OrderID, "correlationId", msg.CorrelationID, "error", err)
			c.toDLQ(ctx, m)
			continue
		}
		c.log.Info("compensation handled", "orderId", msg.OrderID, "correlationId", msg.CorrelationID, "offset", m.Offset)
	}
}

func (c *CancellationConsumer) decode(ctx context.Context, m kafka.Message) (CancellationMsg, error) {
	var msg CancellationMsg
	if len(m.Value) < wireHeaderLen {
		return msg, fmt.Errorf("record too short (%d bytes)", len(m.Value))
	}
	if m.Value[0] != wireMagic {
		return msg, fmt.Errorf("bad confluent magic byte 0x%02x", m.Value[0])
	}
	schemaID := int32(binary.BigEndian.Uint32(m.Value[1:5]))
	schema, err := c.schemas.ByID(ctx, schemaID)
	if err != nil {
		return msg, err
	}
	var rec map[string]any
	if err := avro.Unmarshal(schema, m.Value[wireHeaderLen:], &rec); err != nil {
		return msg, fmt.Errorf("avro decode: %w", err)
	}
	msg.OrderID = str(rec["orderId"])
	msg.Reason = str(rec["reason"])
	if hdr := header(m, headerCorrId); hdr != "" {
		msg.CorrelationID = hdr
	} else {
		msg.CorrelationID = str(rec["correlationId"])
	}
	if msg.OrderID == "" {
		return msg, fmt.Errorf("decoded record missing orderId")
	}
	return msg, nil
}

func (c *CancellationConsumer) toDLQ(ctx context.Context, m kafka.Message) {
	if err := c.dlq.WriteMessages(ctx, kafka.Message{
		Topic: CancellationDLQTopic,
		Key:   m.Key,
		Value: m.Value,
		Headers: append(m.Headers,
			kafka.Header{Key: "original-topic", Value: []byte(m.Topic)}),
	}); err != nil {
		c.log.Error("failed to park record on DLQ", "error", err)
	}
}

func header(m kafka.Message, key string) string {
	for _, h := range m.Headers {
		if h.Key == key {
			return string(h.Value)
		}
	}
	return ""
}

func str(v any) string {
	s, _ := v.(string)
	return s
}

func retry(attempts int, backoff time.Duration, fn func() error) error {
	var err error
	for i := 0; i < attempts; i++ {
		if err = fn(); err == nil {
			return nil
		}
		if i < attempts-1 {
			time.Sleep(backoff)
		}
	}
	return err
}