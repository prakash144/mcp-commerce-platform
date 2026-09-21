package service_test

import (
	"context"
	"errors"
	"testing"

	"github.com/commerce/payment-service/internal/event"
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

func (f *fakeRepo) FindByOrderID(orderID string) (*model.Payment, error) {
	for _, p := range f.payments {
		if p.OrderID == orderID {
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

func (f *fakeRepo) ListPayments(page int, pageSize int, status string) ([]model.Payment, int64, error) {
	var filtered []model.Payment
	for _, p := range f.payments {
		if status == "" || string(p.Status) == status {
			filtered = append(filtered, *p)
		}
	}
	start := page * pageSize
	if start > len(filtered) {
		start = len(filtered)
	}
	end := start + pageSize
	if end > len(filtered) {
		end = len(filtered)
	}
	return filtered[start:end], int64(len(filtered)), nil
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

func TestListPaymentsOverAllStatuses(t *testing.T) {
	repo := newFakeRepo()
	seedPayment(repo, "11111111-1111-1111-1111-111111111111", model.PaymentStatusCaptured, 10000)
	seedPayment(repo, "22222222-2222-2222-2222-222222222222", model.PaymentStatusRefunded, 5000)
	seedPayment(repo, "33333333-3333-3333-3333-333333333333", model.PaymentStatusCaptured, 2000)

	svc := service.NewPaymentService(repo)
	payments, total, err := svc.ListPayments(context.Background(), 0, 20, "")
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if total != 3 {
		t.Errorf("total = %d, want 3", total)
	}
	if len(payments) != 3 {
		t.Errorf("got %d payments, want 3", len(payments))
	}
}

func TestListPaymentsFilterAndPaginate(t *testing.T) {
	repo := newFakeRepo()
	seedPayment(repo, "11111111-1111-1111-1111-111111111111", model.PaymentStatusCaptured, 10000)
	seedPayment(repo, "22222222-2222-2222-2222-222222222222", model.PaymentStatusRefunded, 5000)
	seedPayment(repo, "33333333-3333-3333-3333-333333333333", model.PaymentStatusCaptured, 2000)

	svc := service.NewPaymentService(repo)

	payments, total, err := svc.ListPayments(context.Background(), 0, 1, "PAYMENT_STATUS_CAPTURED")
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if total != 2 {
		t.Errorf("total = %d, want 2", total)
	}
	if len(payments) != 1 {
		t.Errorf("got %d payments on page 1, want 1", len(payments))
	}
	if string(payments[0].Status) != "CAPTURED" {
		t.Errorf("status = %s, want CAPTURED", payments[0].Status)
	}

	payments, _, err = svc.ListPayments(context.Background(), 1, 1, "PAYMENT_STATUS_CAPTURED")
	if err != nil {
		t.Fatalf("list page 2: %v", err)
	}
	if len(payments) != 1 {
		t.Errorf("got %d payments on page 2, want 1", len(payments))
	}
}

func TestListPaymentsClampsPagination(t *testing.T) {
	repo := newFakeRepo()
	seedPayment(repo, "11111111-1111-1111-1111-111111111111", model.PaymentStatusCaptured, 10000)
	svc := service.NewPaymentService(repo)

	payments, total, err := svc.ListPayments(context.Background(), -1, 0, "")
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if total != 1 {
		t.Errorf("total = %d, want 1", total)
	}
	if len(payments) != 1 {
		t.Errorf("got %d payments, want 1 (clamped)", len(payments))
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

// eventRecorder implements service.EventPublisher, capturing emitted fact
// events for assertions without needing Kafka.
type eventRecorder struct {
	succeeded []event.PaymentSucceeded
	failed    []event.PaymentFailed
	refunded  []event.PaymentRefunded
	voided    []event.PaymentVoided
}

func (r *eventRecorder) PaymentSucceeded(_ context.Context, e event.PaymentSucceeded) error {
	r.succeeded = append(r.succeeded, e)
	return nil
}

func (r *eventRecorder) PaymentFailed(_ context.Context, e event.PaymentFailed) error {
	r.failed = append(r.failed, e)
	return nil
}

func (r *eventRecorder) PaymentRefunded(_ context.Context, e event.PaymentRefunded) error {
	r.refunded = append(r.refunded, e)
	return nil
}

func (r *eventRecorder) PaymentVoided(_ context.Context, e event.PaymentVoided) error {
	r.voided = append(r.voided, e)
	return nil
}

func TestChargePublishesSucceeded(t *testing.T) {
	repo := newFakeRepo()
	rec := &eventRecorder{}
	svc := service.NewPaymentService(repo, service.WithEventPublisher(rec))

	p, err := svc.Charge(context.Background(), service.ChargeInput{
		IdempotencyKey: "idem-1",
		OrderID:        "order-9",
		CustomerID:     "customer-1",
		AmountMinor:    4950,
		Currency:       "usd",
		Method:         model.PaymentMethodCard,
	})
	if err != nil {
		t.Fatalf("charge: %v", err)
	}
	if len(rec.succeeded) != 1 {
		t.Fatalf("got %d succeeded events, want 1", len(rec.succeeded))
	}
	e := rec.succeeded[0]
	if e.OrderID != "order-9" || e.PaymentID != p.ID || e.AmountMinor != 4950 || e.Currency != "USD" {
		t.Errorf("unexpected succeeded event: %+v", e)
	}
}

func TestChargeReplayDoesNotRepublish(t *testing.T) {
	repo := newFakeRepo()
	rec := &eventRecorder{}
	svc := service.NewPaymentService(repo, service.WithEventPublisher(rec))
	repo.payments["11111111-1111-1111-1111-111111111111"] = &model.Payment{
		ID: "11111111-1111-1111-1111-111111111111", OrderID: "order-9",
		IdempotencyKey: "idem-1", Status: model.PaymentStatusCaptured,
	}

	if _, err := svc.Charge(context.Background(), service.ChargeInput{
		IdempotencyKey: "idem-1", OrderID: "order-9", AmountMinor: 1000, Currency: "USD",
		Method: model.PaymentMethodCard,
	}); err != nil {
		t.Fatalf("replay charge: %v", err)
	}
	if len(rec.succeeded) != 0 {
		t.Errorf("got %d succeeded events on replay, want 0", len(rec.succeeded))
	}
}

func TestHandleOrderCancelledRefundsCaptured(t *testing.T) {
	repo := newFakeRepo()
	rec := &eventRecorder{}
	svc := service.NewPaymentService(repo, service.WithEventPublisher(rec))
	seedPayment(repo, "11111111-1111-1111-1111-111111111111", model.PaymentStatusCaptured, 10000)

	ctx := service.WithCorrelation(context.Background(), "cid-saga-1")
	if err := svc.HandleOrderCancelled(ctx, "order-1", "customer changed mind"); err != nil {
		t.Fatalf("handle cancelled: %v", err)
	}
	p := repo.payments["11111111-1111-1111-1111-111111111111"]
	if p.Status != model.PaymentStatusRefunded {
		t.Fatalf("status = %s, want REFUNDED", p.Status)
	}
	if len(rec.refunded) != 1 {
		t.Fatalf("got %d refunded events, want 1", len(rec.refunded))
	}
	if e := rec.refunded[0]; e.OrderID != "order-1" || e.PaymentID != p.ID || e.AmountMinor != 10000 || e.CorrelationID != "cid-saga-1" {
		t.Errorf("unexpected refunded event: %+v", e)
	}
}

func TestHandleOrderCancelledVoidsAuthorized(t *testing.T) {
	repo := newFakeRepo()
	rec := &eventRecorder{}
	svc := service.NewPaymentService(repo, service.WithEventPublisher(rec))
	seedPayment(repo, "11111111-1111-1111-1111-111111111111", model.PaymentStatusAuthorized, 10000)

	if err := svc.HandleOrderCancelled(context.Background(), "order-1", "manual review"); err != nil {
		t.Fatalf("handle cancelled: %v", err)
	}
	p := repo.payments["11111111-1111-1111-1111-111111111111"]
	if p.Status != model.PaymentStatusVoided {
		t.Fatalf("status = %s, want VOIDED", p.Status)
	}
	if len(rec.voided) != 1 || rec.voided[0].OrderID != "order-1" {
		t.Errorf("unexpected voided events: %+v", rec.voided)
	}
}

func TestHandleOrderCancelledAlreadyRefundedIsNoop(t *testing.T) {
	repo := newFakeRepo()
	rec := &eventRecorder{}
	svc := service.NewPaymentService(repo, service.WithEventPublisher(rec))
	seedPayment(repo, "11111111-1111-1111-1111-111111111111", model.PaymentStatusRefunded, 10000)

	if err := svc.HandleOrderCancelled(context.Background(), "order-1", "duplicate delivery"); err != nil {
		t.Fatalf("handle cancelled: %v", err)
	}
	if got := len(repo.refunds); got != 0 {
		t.Errorf("created %d refunds, want 0", got)
	}
	if len(rec.refunded)+len(rec.voided) != 0 {
		t.Errorf("unexpected events: refunded=%d voided=%d", len(rec.refunded), len(rec.voided))
	}
}

func TestHandleOrderCancelledUnknownOrderIsNoop(t *testing.T) {
	repo := newFakeRepo()
	rec := &eventRecorder{}
	svc := service.NewPaymentService(repo, service.WithEventPublisher(rec))

	if err := svc.HandleOrderCancelled(context.Background(), "no-such-order", "tidy"); err != nil {
		t.Fatalf("handle cancelled: %v", err)
	}
	if len(rec.refunded)+len(rec.voided) != 0 {
		t.Errorf("unexpected events: refunded=%d voided=%d", len(rec.refunded), len(rec.voided))
	}
}

func TestHandleOrderCancelledReplayIsIdempotent(t *testing.T) {
	repo := newFakeRepo()
	rec := &eventRecorder{}
	svc := service.NewPaymentService(repo, service.WithEventPublisher(rec))
	seedPayment(repo, "11111111-1111-1111-1111-111111111111", model.PaymentStatusCaptured, 10000)

	for i := 0; i < 2; i++ {
		if err := svc.HandleOrderCancelled(context.Background(), "order-1", "duplicate cancel"); err != nil {
			t.Fatalf("attempt %d: %v", i, err)
		}
	}
	if got := len(repo.refunds); got != 1 {
		t.Errorf("stored %d refunds, want 1 (dedup by cancel:<orderID> key)", got)
	}
	if len(rec.refunded) != 1 {
		t.Errorf("published %d refunded events, want 1", len(rec.refunded))
	}
}
