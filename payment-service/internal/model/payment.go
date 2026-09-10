package model

import (
	"time"
)

type PaymentStatus string

const (
	PaymentStatusPending           PaymentStatus = "PENDING"
	PaymentStatusAuthorized        PaymentStatus = "AUTHORIZED"
	PaymentStatusCaptured          PaymentStatus = "CAPTURED"
	PaymentStatusRefunded          PaymentStatus = "REFUNDED"
	PaymentStatusPartiallyRefunded PaymentStatus = "PARTIALLY_REFUNDED"
	PaymentStatusVoided            PaymentStatus = "VOIDED"
	PaymentStatusFailed            PaymentStatus = "FAILED"
)

type PaymentMethod string

const (
	PaymentMethodCard         PaymentMethod = "CARD"
	PaymentMethodBankTransfer PaymentMethod = "BANK_TRANSFER"
	PaymentMethodWallet       PaymentMethod = "WALLET"
)

type Payment struct {
	ID             string        `gorm:"primaryKey;type:uuid"`
	OrderID        string        `gorm:"type:uuid;not null;index"`
	CustomerID     string        `gorm:"type:uuid;not null"`
	AmountMinor    int64         `gorm:"not null"`
	Currency       string        `gorm:"type:varchar(3);not null"`
	Status         PaymentStatus `gorm:"type:varchar(32);not null"`
	Method         PaymentMethod `gorm:"type:varchar(32);not null"`
	IdempotencyKey string        `gorm:"type:varchar(128);not null;uniqueIndex"`
	FailureReason  string        `gorm:"type:varchar(255)"`
	CreatedAt      time.Time
	UpdatedAt      time.Time
}

type Refund struct {
	ID             string `gorm:"primaryKey;type:uuid"`
	PaymentID      string `gorm:"type:uuid;not null;index"`
	AmountMinor    int64  `gorm:"not null"`
	Currency       string `gorm:"type:varchar(3);not null"`
	Reason         string `gorm:"type:varchar(255)"`
	IdempotencyKey string `gorm:"type:varchar(128);not null;uniqueIndex"`
	CreatedAt      time.Time
}
