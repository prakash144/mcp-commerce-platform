export interface Product {
  id: string
  name: string
  description: string
  price: number
  sku: string
  stock: number
  imageUrl?: string | null
  createdAt: string
  updatedAt: string
}

export interface ProductPage {
  content: Product[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export type OrderStatus = 'PENDING' | 'CONFIRMED' | 'CANCELLED' | 'FAILED'

export interface OrderItem {
  id: string
  productId: string
  productName: string
  unitPrice: number
  quantity: number
  lineTotal: number
}

export interface Order {
  id: string
  customerId: string
  status: OrderStatus
  totalAmount: number
  currency: string
  paymentId?: string | null
  chargeAttempts?: number
  items: OrderItem[]
  createdAt: string
  updatedAt: string
}

export interface CartItem {
  product: Product
  quantity: number
}

export type PaymentStatus =
  | 'PENDING'
  | 'AUTHORIZED'
  | 'CAPTURED'
  | 'REFUNDED'
  | 'PARTIALLY_REFUNDED'
  | 'VOIDED'
  | 'FAILED'

export interface Payment {
  id: string
  orderId: string
  customerId: string
  amountMinor: number
  currency: string
  status: string
  method: string
  createdAt: string
  updatedAt: string
  failureReason?: string
}

export interface PaymentPage {
  payments: Payment[]
  totalCount: number
}

export interface OrderStats {
  totalOrders: number
  revenue: number
}