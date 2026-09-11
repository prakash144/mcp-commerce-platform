import type { BadgeProps } from '../../components/ui/badge'
import { Badge } from '../../components/ui/badge'

const ORDER_VARIANTS: Record<string, NonNullable<BadgeProps['variant']>> = {
  PENDING: 'warning',
  CONFIRMED: 'success',
  CANCELLED: 'destructive',
}

const PAYMENT_VARIANTS: Record<string, NonNullable<BadgeProps['variant']>> = {
  PENDING: 'warning',
  AUTHORIZED: 'default',
  CAPTURED: 'success',
  PARTIALLY_REFUNDED: 'warning',
  REFUNDED: 'default',
  VOIDED: 'outline',
  FAILED: 'destructive',
}

export function orderStatusVariant(status: string): NonNullable<BadgeProps['variant']> {
  return ORDER_VARIANTS[status] ?? 'default'
}

export function paymentStatusVariant(status: string): NonNullable<BadgeProps['variant']> {
  return PAYMENT_VARIANTS[status] ?? 'default'
}

export function paymentLabel(status: string): string {
  return status.replace(/^PAYMENT_STATUS_/, '').replace(/_/g, ' ').toLowerCase().replace(/^\w/, (c) => c.toUpperCase())
}

export function OrderStatusBadge({ status }: { status: string }) {
  return <Badge variant={orderStatusVariant(status)}>{status}</Badge>
}

export function PaymentStatusBadge({ status }: { status: string }) {
  return <Badge variant={paymentStatusVariant(status)}>{paymentLabel(status)}</Badge>
}