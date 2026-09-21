package service

import (
	"context"
	"errors"
	"log/slog"
	"strings"
	"time"

	"github.com/commerce/payment-service/internal/event"
	"github.com/commerce/payment-service/internal/model"
	"github.com/commerce/payment-service/internal/repository"
	"github.com/google/uuid"
)

var (
	ErrPaymentNotFound  = errors.New("payment not found")
	ErrInvalidAmount    = errors.New("amount must be greater than zero")
	ErrInvalidCurrency  = errors.New("currency must be a 3-letter ISO 4217 code")
	ErrIllegalState     = errors.New("operation not allowed in current payment state")
	ErrMissingKey       = errors.New("idempotency key is required")
	ErrInvalidPaymentID = errors.New("payment id must be a valid UUID")
)

type ChargeInput struct {
	IdempotencyKey string
	OrderID        string
	CustomerID     string
	AmountMinor    int64
	Currency       string
	Method         model.PaymentMethod
}

type PaymentService interface {
	Charge(ctx context.Context, in ChargeInput) (*model.Payment, error)
	Capture(ctx context.Context, paymentID string, amountMinor int64) (*model.Payment, error)
	Void(ctx context.Context, paymentID, reason string) (*model.Payment, error)
	Refund(ctx context.Context, paymentID, idempotencyKey string, amountMinor int64, reason string) (*model.Payment, *model.Refund, error)
	GetByID(ctx context.Context, id string) (*model.Payment, error)
	ListPayments(ctx context.Context, page int, pageSize int, status string) ([]model.Payment, int64, error)
	// HandleOrderCancelled runs the saga compensation for an OrderCancelled
	// fact: refunds a captured payment or voids an authorized one.
	HandleOrderCancelled(ctx context.Context, orderID, reason string) error
}

// EventPublisher publishes payment-fact events after a settlement commits.
// The concrete implementation is the Kafka-backed event.Publisher; tests use
// a no-op to keep the existing assertions focused on state transitions.
type EventPublisher interface {
	PaymentSucceeded(ctx context.Context, e event.PaymentSucceeded) error
	PaymentFailed(ctx context.Context, e event.PaymentFailed) error
	PaymentRefunded(ctx context.Context, e event.PaymentRefunded) error
	PaymentVoided(ctx context.Context, e event.PaymentVoided) error
}

type paymentService struct {
	repo   repository.PaymentRepository
	events EventPublisher
}

// Option configures a PaymentService at construction time.
type Option func(*paymentService)

// WithEventPublisher attaches the event publisher used to emit fact events.
func WithEventPublisher(p EventPublisher) Option {
	return func(s *paymentService) { s.events = p }
}

func NewPaymentService(repo repository.PaymentRepository, opts ...Option) PaymentService {
	s := &paymentService{repo: repo}
	for _, opt := range opts {
		opt(s)
	}
	return s
}

func (s *paymentService) Charge(ctx context.Context, in ChargeInput) (*model.Payment, error) {
	if strings.TrimSpace(in.IdempotencyKey) == "" {
		return nil, ErrMissingKey
	}
	if existing, err := s.repo.FindByIdempotencyKey(in.IdempotencyKey); err == nil {
		return existing, nil
	} else if !errors.Is(err, repository.ErrNotFound) {
		return nil, err
	}
	if in.AmountMinor <= 0 {
		return nil, ErrInvalidAmount
	}
	if !validCurrency(in.Currency) {
		return nil, ErrInvalidCurrency
	}

	p := &model.Payment{
		ID:             uuid.NewString(),
		OrderID:        in.OrderID,
		CustomerID:     in.CustomerID,
		AmountMinor:    in.AmountMinor,
		Currency:       strings.ToUpper(in.Currency),
		Status:         model.PaymentStatusCaptured,
		Method:         in.Method,
		IdempotencyKey: in.IdempotencyKey,
		CreatedAt:      time.Now().UTC(),
	}
	if err := s.repo.CreatePayment(p); err != nil {
		if dup, dupErr := s.repo.FindByIdempotencyKey(in.IdempotencyKey); dupErr == nil {
			return dup, nil
		}
		return nil, err
	}
	s.publishSucceeded(ctx, p, in.IdempotencyKey)
	return p, nil
}

func (s *paymentService) Capture(ctx context.Context, paymentID string, amountMinor int64) (*model.Payment, error) {
	if !validUUID(paymentID) {
		return nil, ErrInvalidPaymentID
	}
	p, err := s.repo.FindByID(paymentID)
	if err != nil {
		return nil, mapErr(err)
	}
	if p.Status != model.PaymentStatusAuthorized {
		return nil, ErrIllegalState
	}
	if amountMinor <= 0 || amountMinor > p.AmountMinor {
		return nil, ErrInvalidAmount
	}
	p.Status = model.PaymentStatusCaptured
	if err := s.repo.UpdatePayment(p); err != nil {
		return nil, err
	}
	return p, nil
}

func (s *paymentService) Void(ctx context.Context, paymentID, reason string) (*model.Payment, error) {
	if !validUUID(paymentID) {
		return nil, ErrInvalidPaymentID
	}
	p, err := s.repo.FindByID(paymentID)
	if err != nil {
		return nil, mapErr(err)
	}
	if p.Status != model.PaymentStatusAuthorized {
		return nil, ErrIllegalState
	}
	p.Status = model.PaymentStatusVoided
	if err := s.repo.UpdatePayment(p); err != nil {
		return nil, err
	}
	s.publishVoided(ctx, p)
	return p, nil
}

func (s *paymentService) Refund(ctx context.Context, paymentID, idempotencyKey string, amountMinor int64, reason string) (*model.Payment, *model.Refund, error) {
	if strings.TrimSpace(idempotencyKey) == "" {
		return nil, nil, ErrMissingKey
	}
	if !validUUID(paymentID) {
		return nil, nil, ErrInvalidPaymentID
	}
	if existing, err := s.repo.FindRefundByIdempotencyKey(idempotencyKey); err == nil {
		p, perr := s.repo.FindByID(existing.PaymentID)
		if perr != nil {
			return nil, nil, mapErr(perr)
		}
		return p, existing, nil
	} else if !errors.Is(err, repository.ErrNotFound) {
		return nil, nil, err
	}

	p, err := s.repo.FindByID(paymentID)
	if err != nil {
		return nil, nil, mapErr(err)
	}
	if p.Status != model.PaymentStatusCaptured && p.Status != model.PaymentStatusPartiallyRefunded {
		return nil, nil, ErrIllegalState
	}

	totalRefunded, err := s.repo.TotalRefundedAmount(paymentID)
	if err != nil {
		return nil, nil, err
	}
	remaining := p.AmountMinor - totalRefunded
	if remaining <= 0 {
		return nil, nil, ErrIllegalState
	}
	if amountMinor < 0 || amountMinor > remaining {
		return nil, nil, ErrInvalidAmount
	}

	refundAmount := amountMinor
	if refundAmount == 0 {
		refundAmount = remaining
	}
	rf := &model.Refund{
		ID:             uuid.NewString(),
		PaymentID:      p.ID,
		AmountMinor:    refundAmount,
		Currency:       p.Currency,
		Reason:         reason,
		IdempotencyKey: idempotencyKey,
		CreatedAt:      time.Now().UTC(),
	}
	if err := s.repo.CreateRefund(rf); err != nil {
		if dup, dupErr := s.repo.FindRefundByIdempotencyKey(idempotencyKey); dupErr == nil {
			p2, perr := s.repo.FindByID(dup.PaymentID)
			if perr != nil {
				return nil, nil, mapErr(perr)
			}
			return p2, dup, nil
		}
		return nil, nil, err
	}

	if totalRefunded+refundAmount >= p.AmountMinor {
		p.Status = model.PaymentStatusRefunded
	} else {
		p.Status = model.PaymentStatusPartiallyRefunded
	}
	if err := s.repo.UpdatePayment(p); err != nil {
		return nil, nil, err
	}
	if p.Status == model.PaymentStatusRefunded {
		s.publishRefunded(ctx, p, rf)
	}
	return p, rf, nil
}

// HandleOrderCancelled compensates an OrderCancelled fact. Payments still
// refundable are refunded in full; authorized-but-uncaptured payments are
// voided; already-terminal payments (REFUNDED/VOIDED/FAILED) are idempotent
// no-ops. Idempotency is guaranteed by the "cancel:<orderID>" refund key and
// the caller's set-once state guard.
func (s *paymentService) HandleOrderCancelled(ctx context.Context, orderID, reason string) error {
	p, err := s.repo.FindByOrderID(orderID)
	if err != nil {
		if errors.Is(err, repository.ErrNotFound) {
			slog.Info("no payment to compensate", "orderId", orderID)
			return nil
		}
		return err
	}
	switch p.Status {
	case model.PaymentStatusCaptured, model.PaymentStatusPartiallyRefunded:
		_, _, err := s.Refund(ctx, p.ID, "cancel:"+orderID, 0, reason)
		return err
	case model.PaymentStatusAuthorized:
		_, err := s.Void(ctx, p.ID, reason)
		return err
	default:
		// REFUNDED / VOIDED / FAILED / PENDING — nothing left to compensate.
		return nil
	}
}

func (s *paymentService) publishSucceeded(ctx context.Context, p *model.Payment, idempotencyKey string) {
	if s.events == nil {
		return
	}
	if err := s.events.PaymentSucceeded(ctx, event.PaymentSucceeded{
		OrderID:       p.OrderID,
		PaymentID:     p.ID,
		IdempotencyKey: idempotencyKey,
		AmountMinor:   p.AmountMinor,
		Currency:      p.Currency,
		CorrelationID: CorrelationFrom(ctx),
	}); err != nil {
		// Best-effort publication: the settlement already completed; the event
		// is a fact for consumers, not a dependency of this state machine.
		slog.Error("failed to publish PaymentSucceeded", "orderId", p.OrderID, "error", err)
	}
}

func (s *paymentService) publishRefunded(ctx context.Context, p *model.Payment, rf *model.Refund) {
	if s.events == nil {
		return
	}
	if err := s.events.PaymentRefunded(ctx, event.PaymentRefunded{
		OrderID:       p.OrderID,
		PaymentID:     p.ID,
		RefundID:      rf.ID,
		AmountMinor:   rf.AmountMinor,
		Currency:      p.Currency,
		CorrelationID: CorrelationFrom(ctx),
	}); err != nil {
		slog.Error("failed to publish PaymentRefunded", "orderId", p.OrderID, "error", err)
	}
}

func (s *paymentService) publishVoided(ctx context.Context, p *model.Payment) {
	if s.events == nil {
		return
	}
	if err := s.events.PaymentVoided(ctx, event.PaymentVoided{
		OrderID:       p.OrderID,
		PaymentID:     p.ID,
		CorrelationID: CorrelationFrom(ctx),
	}); err != nil {
		slog.Error("failed to publish PaymentVoided", "orderId", p.OrderID, "error", err)
	}
}

func (s *paymentService) GetByID(ctx context.Context, id string) (*model.Payment, error) {
	if !validUUID(id) {
		return nil, ErrInvalidPaymentID
	}
	p, err := s.repo.FindByID(id)
	if err != nil {
		return nil, mapErr(err)
	}
	return p, nil
}

func (s *paymentService) ListPayments(ctx context.Context, page int, pageSize int, status string) ([]model.Payment, int64, error) {
	if page < 0 {
		page = 0
	}
	if pageSize <= 0 || pageSize > 100 {
		pageSize = 20
	}
	status = strings.TrimSpace(status)
	status = strings.TrimPrefix(status, "PAYMENT_STATUS_")
	return s.repo.ListPayments(page, pageSize, status)
}

func mapErr(err error) error {
	if errors.Is(err, repository.ErrNotFound) {
		return ErrPaymentNotFound
	}
	return err
}

func validUUID(id string) bool {
	_, err := uuid.Parse(id)
	return err == nil
}

func validCurrency(c string) bool {
	uc := strings.ToUpper(c)
	if len(uc) != 3 {
		return false
	}
	for _, r := range uc {
		if r < 'A' || r > 'Z' {
			return false
		}
	}
	return true
}
