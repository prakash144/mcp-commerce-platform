export interface Product {
  id: string
  name: string
  description: string
  price: number
  sku: string
  stock: number
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

export type OrderStatus = 'PENDING' | 'CONFIRMED' | 'CANCELLED'

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
  items: OrderItem[]
  createdAt: string
  updatedAt: string
}

export interface CartItem {
  product: Product
  quantity: number
}