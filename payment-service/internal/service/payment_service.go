package service

import (
	"context"
	"errors"
	"strings"
	"time"

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
}

type paymentService struct {
	repo repository.PaymentRepository
}

func NewPaymentService(repo repository.PaymentRepository) PaymentService {
	return &paymentService{repo: repo}
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
	return p, rf, nil
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
