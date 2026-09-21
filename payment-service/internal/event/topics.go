package event

const (
	TopicOrdersCreated   = "orders.created"
	TopicOrdersCancelled = "orders.cancelled"
	TopicPaymentSucceeded = "payments.succeeded"
	TopicPaymentFailed   = "payments.failed"
	TopicPaymentRefunded = "payments.refunded"
	TopicPaymentVoided   = "payments.voided"
)

const (
	// CompensationGroupID is the consumer-group the saga compensation (orders.cancelled) reader joins.
	CompensationGroupID = "payment-compensation"
	// CancellationDLQTopic receives orders.cancelled records that exhaust retries.
	CancellationDLQTopic = "orders.cancelled." + CompensationGroupID + ".DLQ"
)

func subjectFor(topic string) string {
	return topic + "-value"
}