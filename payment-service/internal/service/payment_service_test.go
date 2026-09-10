package service_test

import (
	"context"
	"errors"
	"testing"

	"github.com/commerce/payment-service/internal/model"
	"github.com/commerce/payment-service/internal/repository"
	"github.com/commerce/payment-service/internal/service"
)

type fakeRepo struct {
	payments map[string]*model.Payment
	refunds  map[string]*model.Refund
}

func newFakeRepo() *fakeRepo {
	return &fakeRepo{
		payments: make(map[string]*model.Payment),
		refunds:  make(map[string]*model.Refund),
	}
}

func (f *fakeRepo) CreatePayment(p *model.Payment) error {
	f.payments[p.ID] = p
	return nil
}

func (f *fakeRepo) FindByID(id string) (*model.Payment, error) {
	if p, ok := f.payments[id]; ok {
		return p, nil
	}
	return nil, repository.ErrNotFound
}

func (f *fakeRepo) FindByIdempotencyKey(key string) (*model.Payment, error) {
	for _, p := range f.payments {
		if p.IdempotencyKey == key {
			return p, nil
		}
	}
	return nil, repository.ErrNotFound
}

func (f *fakeRepo) UpdatePayment(p *model.Payment) error {
	f.payments[p.ID] = p
	return nil
}

func (f *fakeRepo) CreateRefund(r *model.Refund) error {
	f.refunds[r.ID] = r
	return nil
}

func (f *fakeRepo) FindRefundByIdempotencyKey(key string) (*model.Refund, error) {
	for _, r := range f.refunds {
		if r.IdempotencyKey == key {
			return r, nil
		}
	}
	return nil, repository.ErrNotFound
}

func (f *fakeRepo) TotalRefundedAmount(paymentID string) (int64, error) {
	var total int64
	for _, r := range f.refunds {
		if r.PaymentID == paymentID {
			total += r.AmountMinor
		}
	}
	return total, nil
}

func seedPayment(repo *fakeRepo, id string, status model.PaymentStatus, amount int64) *model.Payment {
	p := &model.Payment{
		ID:          id,
		OrderID:     "order-1",
		CustomerID:  "customer-1",
		AmountMinor: amount,
		Currency:    "USD",
		Status:      status,
		Method:      model.PaymentMethodCard,
	}
	repo.payments[id] = p
	return p
}

func TestChargeCreatesCapturedPayment(t *testing.T) {
	repo := newFakeRepo()
	svc := service.NewPaymentService(repo)

	p, err := svc.Charge(context.Background(), service.ChargeInput{
		IdempotencyKey: "key-1",
		OrderID:        "order-1",
		CustomerID:     "customer-1",
		AmountMinor:    4999,
		Currency:       "usd",
		Method:         model.PaymentMethodCard,
	})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if p.Status != model.PaymentStatusCaptured {
		t.Errorf("status = %s, want CAPTURED", p.Status)
	}
	if p.Currency != "USD" {
		t.Errorf("currency = %s, want normalized USD", p.Currency)
	}
	if got := len(repo.payments); got != 1 {
		t.Errorf("stored %d payments, want 1", got)
	}
}

func TestChargeReplayReturnsExistingPayment(t *testing.T) {
	repo := newFakeRepo()
	svc := service.NewPaymentService(repo)

	in := service.ChargeInput{
		IdempotencyKey: "key-dup",
		OrderID:        "order-1",
		CustomerID:     "customer-1",
		AmountMinor:    1000,
		Currency:       "USD",
		Method:         model.PaymentMethodCard,
	}
	first, err := svc.Charge(context.Background(), in)
	if err != nil {
		t.Fatalf("first charge: %v", err)
	}
	second, err := svc.Charge(context.Background(), in)
	if err != nil {
		t.Fatalf("replay: %v", err)
	}
	if first.ID != second.ID {
		t.Errorf("replay returned different payment %s vs %s", first.ID, second.ID)
	}
	if got := len(repo.payments); got != 1 {
		t.Errorf("stored %d payments, want 1", got)
	}
}

func TestChargeValidation(t *testing.T) {
	repo := newFakeRepo()
	svc := service.NewPaymentService(repo)

	base := service.ChargeInput{
		IdempotencyKey: "key",
		OrderID:        "order-1",
		CustomerID:     "customer-1",
		AmountMinor:    1000,
		Currency:       "USD",
		Method:         model.PaymentMethodCard,
	}

	cases := []struct {
		name string
		mut  func(*service.ChargeInput)
		want error
	}{
		{"missing idempotency key", func(i *service.ChargeInput) { i.IdempotencyKey = " " }, service.ErrMissingKey},
		{"zero amount", func(i *service.ChargeInput) { i.AmountMinor = 0 }, service.ErrInvalidAmount},
		{"negative amount", func(i *service.ChargeInput) { i.AmountMinor = -5 }, service.ErrInvalidAmount},
		{"bad currency", func(i *service.ChargeInput) { i.Currency = "US" }, service.ErrInvalidCurrency},
		{"numeric currency", func(i *service.ChargeInput) { i.Currency = "123" }, service.ErrInvalidCurrency},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			in := base
			tc.mut(&in)
			_, err := svc.Charge(context.Background(), in)
			if !errors.Is(err, tc.want) {
				t.Errorf("got %v, want %v", err, tc.want)
			}
		})
	}
}

func TestCaptureFromAuthorized(t *testing.T) {
	repo := newFakeRepo()
	svc := service.NewPaymentService(repo)
	seedPayment(repo, "11111111-1111-1111-1111-111111111111", model.PaymentStatusAuthorized, 10000)

	p, err := svc.Capture(context.Background(), "11111111-1111-1111-1111-111111111111", 5000)
	if err != nil {
		t.Fatalf("capture: %v", err)
	}
	if p.Status != model.PaymentStatusCaptured {
		t.Errorf("status = %s, want CAPTURED", p.Status)
	}
}

func TestCaptureIllegalStates(t *testing.T) {
	repo := newFakeRepo()
	svc := service.NewPaymentService(repo)

	cases := []struct {
		name   string
		status model.PaymentStatus
	}{
		{"captured payment", model.PaymentStatusCaptured},
		{"refunded payment", model.PaymentStatusRefunded},
		{"pending payment", model.PaymentStatusPending},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			repo.payments = map[string]*model.Payment{}
			seedPayment(repo, "11111111-1111-1111-1111-111111111111", tc.status, 10000)
			_, err := svc.Capture(context.Background(), "11111111-1111-1111-1111-111111111111", 5000)
			if !errors.Is(err, service.ErrIllegalState) {
				t.Errorf("got %v, want ErrIllegalState", err)
			}
		})
	}
}

func TestCaptureAmountExceedsAuthorized(t *testing.T) {
	repo := newFakeRepo()
	svc := service.NewPaymentService(repo)
	seedPayment(repo, "11111111-1111-1111-1111-111111111111", model.PaymentStatusAuthorized, 10000)

	_, err := svc.Capture(context.Background(), "11111111-1111-1111-1111-111111111111", 15000)
	if !errors.Is(err, service.ErrInvalidAmount) {
		t.Errorf("got %v, want ErrInvalidAmount", err)
	}
}

func TestVoidFromAuthorized(t *testing.T) {
	repo := newFakeRepo()
	svc := service.NewPaymentService(repo)
	seedPayment(repo, "11111111-1111-1111-1111-111111111111", model.PaymentStatusAuthorized, 10000)

	p, err := svc.Void(context.Background(), "11111111-1111-1111-1111-111111111111", "customer changed mind")
	if err != nil {
		t.Fatalf("void: %v", err)
	}
	if p.Status != model.PaymentStatusVoided {
		t.Errorf("status = %s, want VOIDED", p.Status)
	}
}

func TestVoidOnCapturedIsIllegal(t *testing.T) {
	repo := newFakeRepo()
	svc := service.NewPaymentService(repo)
	seedPayment(repo, "11111111-1111-1111-1111-111111111111", model.PaymentStatusCaptured, 10000)

	_, err := svc.Void(context.Background(), "11111111-1111-1111-1111-111111111111", "")
	if !errors.Is(err, service.ErrIllegalState) {
		t.Errorf("got %v, want ErrIllegalState", err)
	}
}

func TestRefundFullSettlement(t *testing.T) {
	repo := newFakeRepo()
	svc := service.NewPaymentService(repo)
	seedPayment(repo, "11111111-1111-1111-1111-111111111111", model.PaymentStatusCaptured, 10000)

	p, rf, err := svc.Refund(context.Background(), "11111111-1111-1111-1111-111111111111", "ref-key-1", 0, "not satisfied")
	if err != nil {
		t.Fatalf("refund: %v", err)
	}
	if p.Status != model.PaymentStatusRefunded {
		t.Errorf("status = %s, want REFUNDED", p.Status)
	}
	if rf.AmountMinor != 10000 {
		t.Errorf("refund amount = %d, want full 10000", rf.AmountMinor)
	}
}

func TestRefundPartial(t *testing.T) {
	repo := newFakeRepo()
	svc := service.NewPaymentService(repo)
	seedPayment(repo, "11111111-1111-1111-1111-111111111111", model.PaymentStatusCaptured, 10000)

	p, rf, err := svc.Refund(context.Background(), "11111111-1111-1111-1111-111111111111", "ref-key-1", 3000, "partial")
	if err != nil {
		t.Fatalf("refund: %v", err)
	}
	if p.Status != model.PaymentStatusPartiallyRefunded {
		t.Errorf("status = %s, want PARTIALLY_REFUNDED", p.Status)
	}
	if rf.AmountMinor != 3000 {
		t.Errorf("refund amount = %d, want 3000", rf.AmountMinor)
	}

	_, rf2, err := svc.Refund(context.Background(), "11111111-1111-1111-1111-111111111111", "ref-key-2", 7000, "rest")
	if err != nil {
		t.Fatalf("second refund: %v", err)
	}
	if repo.payments["11111111-1111-1111-1111-111111111111"].Status != model.PaymentStatusRefunded {
		t.Errorf("status = %s, want REFUNDED after total", repo.payments["11111111-1111-1111-1111-111111111111"].Status)
	}
	if rf2.AmountMinor != 7000 {
		t.Errorf("refund amount = %d, want 7000", rf2.AmountMinor)
	}
}

func TestRefundValidation(t *testing.T) {
	repo := newFakeRepo()
	svc := service.NewPaymentService(repo)
	seedPayment(repo, "11111111-1111-1111-1111-111111111111", model.PaymentStatusCaptured, 10000)

	if _, _, err := svc.Refund(context.Background(), "11111111-1111-1111-1111-111111111111", "", 5000, ""); !errors.Is(err, service.ErrMissingKey) {
		t.Errorf("missing key: got %v, want ErrMissingKey", err)
	}
	if _, _, err := svc.Refund(context.Background(), "11111111-1111-1111-1111-111111111111", "ref-key", 20000, ""); !errors.Is(err, service.ErrInvalidAmount) {
		t.Errorf("overshoot: got %v, want ErrInvalidAmount", err)
	}
}

func TestRefundReplayReturnsSameRefund(t *testing.T) {
	repo := newFakeRepo()
	svc := service.NewPaymentService(repo)
	seedPayment(repo, "11111111-1111-1111-1111-111111111111", model.PaymentStatusCaptured, 10000)

	_, rf1, err := svc.Refund(context.Background(), "11111111-1111-1111-1111-111111111111", "ref-key", 5000, "")
	if err != nil {
		t.Fatalf("first refund: %v", err)
	}
	_, rf2, err := svc.Refund(context.Background(), "11111111-1111-1111-1111-111111111111", "ref-key", 5000, "")
	if err != nil {
		t.Fatalf("replay refund: %v", err)
	}
	if rf1.ID != rf2.ID {
		t.Errorf("replay returned different refund %s vs %s", rf1.ID, rf2.ID)
	}
	if got := len(repo.refunds); got != 1 {
		t.Errorf("stored %d refunds, want 1", got)
	}
	if repo.payments["11111111-1111-1111-1111-111111111111"].Status != model.PaymentStatusPartiallyRefunded {
		t.Errorf("status = %s, want PARTIALLY_REFUNDED", repo.payments["11111111-1111-1111-1111-111111111111"].Status)
	}
}

func TestGetByID(t *testing.T) {
	repo := newFakeRepo()
	svc := service.NewPaymentService(repo)
	seedPayment(repo, "11111111-1111-1111-1111-111111111111", model.PaymentStatusCaptured, 10000)

	if _, err := svc.GetByID(context.Background(), "11111111-1111-1111-1111-111111111111"); err != nil {
		t.Errorf("existing: %v", err)
	}
	if _, err := svc.GetByID(context.Background(), "22222222-2222-2222-2222-222222222222"); !errors.Is(err, service.ErrPaymentNotFound) {
		t.Errorf("missing: got %v, want ErrPaymentNotFound", err)
	}
}

func TestInvalidUUIDRejected(t *testing.T) {
	repo := newFakeRepo()
	svc := service.NewPaymentService(repo)
	seedPayment(repo, "11111111-1111-1111-1111-111111111111", model.PaymentStatusAuthorized, 10000)

	cases := []struct {
		name string
		call func() error
	}{
		{"GetByID malformed", func() error { _, err := svc.GetByID(context.Background(), "not-a-uuid"); return err }},
		{"Capture malformed", func() error { _, err := svc.Capture(context.Background(), "not-a-uuid", 1000); return err }},
		{"Void malformed", func() error { _, err := svc.Void(context.Background(), "not-a-uuid", ""); return err }},
		{"Refund malformed", func() error { _, _, err := svc.Refund(context.Background(), "not-a-uuid", "k", 1000, ""); return err }},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if err := tc.call(); !errors.Is(err, service.ErrInvalidPaymentID) {
				t.Errorf("got %v, want ErrInvalidPaymentID", err)
			}
		})
	}
}
