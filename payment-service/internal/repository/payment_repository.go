package repository

import (
	"errors"

	"github.com/commerce/payment-service/internal/model"
	"gorm.io/gorm"
)

var ErrNotFound = errors.New("record not found")

type PaymentRepository interface {
	CreatePayment(p *model.Payment) error
	FindByID(id string) (*model.Payment, error)
	FindByIdempotencyKey(key string) (*model.Payment, error)
	UpdatePayment(p *model.Payment) error
	CreateRefund(r *model.Refund) error
	TotalRefundedAmount(paymentID string) (int64, error)
	FindRefundByIdempotencyKey(key string) (*model.Refund, error)
}

type paymentRepository struct {
	db *gorm.DB
}

func NewPaymentRepository(db *gorm.DB) PaymentRepository {
	return &paymentRepository{db: db}
}

func (r *paymentRepository) CreatePayment(p *model.Payment) error {
	return r.db.Create(p).Error
}

func (r *paymentRepository) FindByID(id string) (*model.Payment, error) {
	var p model.Payment
	err := r.db.First(&p, "id = ?", id).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	return &p, nil
}

func (r *paymentRepository) FindByIdempotencyKey(key string) (*model.Payment, error) {
	var p model.Payment
	err := r.db.First(&p, "idempotency_key = ?", key).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	return &p, nil
}

func (r *paymentRepository) UpdatePayment(p *model.Payment) error {
	return r.db.Save(p).Error
}

func (r *paymentRepository) CreateRefund(rf *model.Refund) error {
	return r.db.Create(rf).Error
}

func (r *paymentRepository) TotalRefundedAmount(paymentID string) (int64, error) {
	var total int64
	err := r.db.Model(&model.Refund{}).
		Where("payment_id = ?", paymentID).
		Select("COALESCE(SUM(amount_minor), 0)").
		Scan(&total).Error
	if err != nil {
		return 0, err
	}
	return total, nil
}

func (r *paymentRepository) FindRefundByIdempotencyKey(key string) (*model.Refund, error) {
	var rf model.Refund
	err := r.db.First(&rf, "idempotency_key = ?", key).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	return &rf, nil
}
