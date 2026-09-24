package event

import (
	"context"
	"encoding/binary"
	"os"
	"path/filepath"
	"testing"

	avro "github.com/hamba/avro/v2"
	"github.com/segmentio/kafka-go"
)

const avscDir = "../../../common/events/avro"

func schemaFromFile(t *testing.T, name string) avro.Schema {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join(avscDir, name))
	if err != nil {
		t.Fatalf("read %s: %v", name, err)
	}
	s, err := avro.Parse(string(raw))
	if err != nil {
		t.Fatalf("parse %s: %v", name, err)
	}
	return s
}

// TestPublishPayloadSlotConformance verifies that the exact field maps the
// producer emits encode against the committed .avsc contracts (field names,
// types, and required fields). This catches rename/typo drift without needing
// a live Schema Registry: registration itself will otherwise only surface it
// at runtime.
func TestPublishPayloadConformance(t *testing.T) {
	cases := []struct {
		name    string
		avsc    string
		fields  func() map[string]any
	}{
		{"PaymentSucceeded", "PaymentSucceeded.avsc", func() map[string]any {
			return paymentSucceededFields(PaymentSucceeded{
				OrderID: "order-1", PaymentID: "pay-1", IdempotencyKey: "idem-1",
				AmountMinor: 4950, Currency: "USD", OccurredAt: "2026-09-22T08:00:00Z", CorrelationID: "cid-1",
			})
		}},
		{"PaymentFailed", "PaymentFailed.avsc", func() map[string]any {
			return paymentFailedFields(PaymentFailed{
				OrderID: "order-1", PaymentID: "pay-1", ErrorCode: "INSUFFICIENT_FUNDS",
				Attempts: 3, OccurredAt: "2026-09-22T08:00:00Z", CorrelationID: "cid-1",
			})
		}},
		{"PaymentRefunded", "PaymentRefunded.avsc", func() map[string]any {
			return paymentRefundedFields(PaymentRefunded{
				OrderID: "order-1", PaymentID: "pay-1", RefundID: "ref-1",
				AmountMinor: 4950, Currency: "USD", OccurredAt: "2026-09-22T08:00:00Z", CorrelationID: "cid-1",
			})
		}},
		{"PaymentVoided", "PaymentVoided.avsc", func() map[string]any {
			return paymentVoidedFields(PaymentVoided{
				OrderID: "order-1", PaymentID: "pay-1", OccurredAt: "2026-09-22T08:00:00Z", CorrelationID: "cid-1",
			})
		}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			s := schemaFromFile(t, tc.avsc)
			if _, err := avro.Marshal(s, tc.fields()); err != nil {
				t.Fatalf("payload does not conform to %s: %v", tc.avsc, err)
			}
		})
	}
}

// TestCancellationDecodeRoundTrip exercises the Confluent wire-format decode
// path (magic byte + schema id + Avro body) against the OrderCancelled
// contract, i.e. the consumer side of the saga compensation trigger.
func TestCancellationDecodeRoundTrip(t *testing.T) {
	s := schemaFromFile(t, "OrderCancelled.avsc")
	const schemaID = int32(42)

	payload, err := avro.Marshal(s, map[string]any{
		"orderId": "order-1", "reason": "customer changed mind",
		"occurredAt": "2026-09-22T08:00:00Z", "correlationId": "cid-9",
	})
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	value := make([]byte, wireHeaderLen+len(payload))
	value[0] = wireMagic
	binary.BigEndian.PutUint32(value[1:5], uint32(schemaID))
	copy(value[wireHeaderLen:], payload)

	c := &CancellationConsumer{schemas: &schemaCache{byID: map[int32]avro.Schema{schemaID: s}}}
	msg, err := c.decode(context.Background(), kafka.Message{
		Value: value,
		Headers: []kafka.Header{
			{Key: "correlationId", Value: []byte("cid-9")},
		},
	})
	if err != nil {
		t.Fatalf("decode: %v", err)
	}
	if msg.OrderID != "order-1" || msg.Reason != "customer changed mind" || msg.CorrelationID != "cid-9" {
		t.Fatalf("unexpected decoded message: %+v", msg)
	}
}

// TestCancellationDecodeRejectsPoison enforces the poison/undefined contract:
// a record that fails to decode must return an error (the consumer DLQs it)
// rather than silently returning garbage.
func TestCancellationDecodeRejectsPoison(t *testing.T) {
	c := &CancellationConsumer{schemas: &schemaCache{byID: map[int32]avro.Schema{1: schemaFromFile(t, "OrderCancelled.avsc")}}}
	// valid confluent header (schema id 1) but invalid Avro body
	value := []byte{0x00, 0x00, 0x00, 0x00, 0x01, 0xde, 0xad}
	if _, err := c.decode(context.Background(), kafka.Message{Value: value}); err == nil {
		t.Fatal("expected decode error for poison payload")
	}
}