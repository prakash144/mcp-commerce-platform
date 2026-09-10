package handler

import (
	"context"
	"errors"
	"log"

	"github.com/commerce/payment-service/internal/model"
	"github.com/commerce/payment-service/internal/service"
	paymentv1 "github.com/commerce/payment-service/pkg/generated"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

type PaymentHandler struct {
	paymentv1.UnimplementedPaymentServiceServer
	svc service.PaymentService
}

func NewPaymentHandler(svc service.PaymentService) *PaymentHandler {
	return &PaymentHandler{svc: svc}
}

func (h *PaymentHandler) Charge(ctx context.Context, req *paymentv1.ChargeRequest) (*paymentv1.ChargeResponse, error) {
	method, err := toMethod(req.GetMethod())
	if err != nil {
		return nil, err
	}
	p, err := h.svc.Charge(ctx, service.ChargeInput{
		IdempotencyKey: req.GetIdempotencyKey(),
		OrderID:        req.GetOrderId(),
		CustomerID:     req.GetCustomerId(),
		AmountMinor:    req.GetAmountMinor(),
		Currency:       req.GetCurrency(),
		Method:         method,
	})
	if err != nil {
		return nil, toGRPCError(err)
	}
	return &paymentv1.ChargeResponse{
		Payment:        toPaymentProto(p),
		IdempotencyKey: req.GetIdempotencyKey(),
	}, nil
}

func (h *PaymentHandler) Refund(ctx context.Context, req *paymentv1.RefundRequest) (*paymentv1.RefundResponse, error) {
	p, rf, err := h.svc.Refund(ctx, req.GetPaymentId(), req.GetIdempotencyKey(), req.GetAmountMinor(), req.GetReason())
	if err != nil {
		return nil, toGRPCError(err)
	}
	return &paymentv1.RefundResponse{
		Payment: toPaymentProto(p),
		Refund:  toRefundProto(rf),
	}, nil
}

func (h *PaymentHandler) Capture(ctx context.Context, req *paymentv1.CaptureRequest) (*paymentv1.CaptureResponse, error) {
	p, err := h.svc.Capture(ctx, req.GetPaymentId(), req.GetAmountMinor())
	if err != nil {
		return nil, toGRPCError(err)
	}
	return &paymentv1.CaptureResponse{Payment: toPaymentProto(p)}, nil
}

func (h *PaymentHandler) Void(ctx context.Context, req *paymentv1.VoidRequest) (*paymentv1.VoidResponse, error) {
	p, err := h.svc.Void(ctx, req.GetPaymentId(), req.GetReason())
	if err != nil {
		return nil, toGRPCError(err)
	}
	return &paymentv1.VoidResponse{Payment: toPaymentProto(p)}, nil
}

func (h *PaymentHandler) GetPayment(ctx context.Context, req *paymentv1.GetPaymentRequest) (*paymentv1.GetPaymentResponse, error) {
	p, err := h.svc.GetByID(ctx, req.GetId())
	if err != nil {
		return nil, toGRPCError(err)
	}
	return &paymentv1.GetPaymentResponse{Payment: toPaymentProto(p)}, nil
}

func toGRPCError(err error) error {
	switch {
	case errors.Is(err, service.ErrPaymentNotFound):
		return status.Error(codes.NotFound, err.Error())
	case errors.Is(err, service.ErrMissingKey),
		errors.Is(err, service.ErrInvalidAmount),
		errors.Is(err, service.ErrInvalidCurrency):
		return status.Error(codes.InvalidArgument, err.Error())
	case errors.Is(err, service.ErrIllegalState):
		return status.Error(codes.FailedPrecondition, err.Error())
	default:
		log.Printf("internal error: %v", err)
		return status.Error(codes.Internal, "internal error")
	}
}

func toMethod(m paymentv1.PaymentMethod) (model.PaymentMethod, error) {
	switch m {
	case paymentv1.PaymentMethod_PAYMENT_METHOD_CARD:
		return model.PaymentMethodCard, nil
	case paymentv1.PaymentMethod_PAYMENT_METHOD_BANK_TRANSFER:
		return model.PaymentMethodBankTransfer, nil
	case paymentv1.PaymentMethod_PAYMENT_METHOD_WALLET:
		return model.PaymentMethodWallet, nil
	default:
		return "", status.Error(codes.InvalidArgument, "payment method is required")
	}
}

func toPaymentProto(p *model.Payment) *paymentv1.Payment {
	return &paymentv1.Payment{
		Id:            p.ID,
		OrderId:       p.OrderID,
		CustomerId:    p.CustomerID,
		AmountMinor:   p.AmountMinor,
		Currency:      p.Currency,
		Status:        toStatusProto(p.Status),
		Method:        toMethodProto(p.Method),
		CreatedAt:     p.CreatedAt.Format("2006-01-02T15:04:05Z07:00"),
		UpdatedAt:     p.UpdatedAt.Format("2006-01-02T15:04:05Z07:00"),
		FailureReason: p.FailureReason,
	}
}

func toRefundProto(r *model.Refund) *paymentv1.Refund {
	return &paymentv1.Refund{
		Id:          r.ID,
		PaymentId:   r.PaymentID,
		AmountMinor: r.AmountMinor,
		Currency:    r.Currency,
		Reason:      r.Reason,
		CreatedAt:   r.CreatedAt.Format("2006-01-02T15:04:05Z07:00"),
	}
}

func toStatusProto(s model.PaymentStatus) paymentv1.PaymentStatus {
	switch s {
	case model.PaymentStatusPending:
		return paymentv1.PaymentStatus_PAYMENT_STATUS_PENDING
	case model.PaymentStatusAuthorized:
		return paymentv1.PaymentStatus_PAYMENT_STATUS_AUTHORIZED
	case model.PaymentStatusCaptured:
		return paymentv1.PaymentStatus_PAYMENT_STATUS_CAPTURED
	case model.PaymentStatusRefunded:
		return paymentv1.PaymentStatus_PAYMENT_STATUS_REFUNDED
	case model.PaymentStatusPartiallyRefunded:
		return paymentv1.PaymentStatus_PAYMENT_STATUS_PARTIALLY_REFUNDED
	case model.PaymentStatusVoided:
		return paymentv1.PaymentStatus_PAYMENT_STATUS_VOIDED
	case model.PaymentStatusFailed:
		return paymentv1.PaymentStatus_PAYMENT_STATUS_FAILED
	default:
		return paymentv1.PaymentStatus_PAYMENT_STATUS_UNSPECIFIED
	}
}

func toMethodProto(m model.PaymentMethod) paymentv1.PaymentMethod {
	switch m {
	case model.PaymentMethodCard:
		return paymentv1.PaymentMethod_PAYMENT_METHOD_CARD
	case model.PaymentMethodBankTransfer:
		return paymentv1.PaymentMethod_PAYMENT_METHOD_BANK_TRANSFER
	case model.PaymentMethodWallet:
		return paymentv1.PaymentMethod_PAYMENT_METHOD_WALLET
	default:
		return paymentv1.PaymentMethod_PAYMENT_METHOD_UNSPECIFIED
	}
}
