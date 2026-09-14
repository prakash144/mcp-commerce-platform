import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { correlationId } from '../lib/trace'
import { ApiError } from './products'
import type { Order, OrderStats, Payment, PaymentPage, Product, ProductPage } from './types'

export interface ProductInput {
  name: string
  sku: string
  price: number
  stock: number
  description?: string
  imageUrl?: string
}

async function send<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      'X-Correlation-Id': correlationId(),
      ...init?.headers,
    },
  })
  if (!res.ok) {
    const message =
      res.status === 404
        ? 'The requested resource was not found.'
        : `Request failed (${res.status}).`
    throw new ApiError(res.status, message)
  }
  if (res.status === 204) return undefined as T
  return res.json() as Promise<T>
}

interface GraphQLResponse<T> {
  data?: T
  errors?: { message: string; extensions?: { code?: string } }[]
}

async function gql<T>(query: string, variables: Record<string, unknown>): Promise<T> {
  const res = await fetch('/graphql', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Correlation-Id': correlationId() },
    body: JSON.stringify({ query, variables }),
  })
  const body = (await res.json()) as GraphQLResponse<T>
  if (body.errors?.length) {
    const first = body.errors[0]
    const err = new Error(
      first.extensions?.code ?? first.message,
    ) as Error & { code?: string; status?: number }
    err.code = first.extensions?.code
    if (/not found/i.test(first.message)) err.status = 404
    throw err
  }
  if (body.data == null) throw new Error('Unexpected empty GraphQL response')
  return body.data
}

export const adminKeys = {
  orderStats: ['admin', 'order-stats'] as const,
  orders: (status: string, page: number) => ['admin', 'orders', status, page] as const,
  payments: (status: string, page: number) => ['admin', 'payments', status, page] as const,
}

export function useOrderStats() {
  return useQuery({
    queryKey: adminKeys.orderStats,
    queryFn: () =>
      gql<{ orderStats: OrderStats }>(
        `query OrderStats {
          orderStats { totalOrders revenue }
        }`,
        {},
      ).then((d) => d.orderStats),
  })
}

export function useAdminOrders(status: string, page: number, size = 20) {
  return useQuery({
    queryKey: adminKeys.orders(status, page),
    queryFn: () =>
      gql<{ orders: { orders: Order[]; totalCount: number } }>(
        `query AdminOrders($first: Int!, $offset: Int!, $status: OrderStatus) {
          orders(first: $first, offset: $offset, status: $status) {
            totalCount
            orders {
              id
              customerId
              status
              totalAmount
              currency
              createdAt
              items { id productId productName unitPrice quantity lineTotal }
            }
          }
        }`,
        {
          first: size,
          offset: page * size,
          status: status === 'ALL' ? null : status,
        },
      ).then((d) => d.orders),
  })
}

export function useCancelOrder() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: string) =>
      gql<{ cancelOrder: Order }>(
        `mutation CancelOrder($id: ID!) {
          cancelOrder(id: $id) { id status }
        }`,
        { id },
      ).then((d) => d.cancelOrder),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['admin'] })
    },
  })
}

export function useAdminProducts(page = 0, size = 20) {
  return useQuery({
    queryKey: ['admin', 'products', page],
    queryFn: () => send<ProductPage>(`/api/v1/products?page=${page}&size=${size}`),
  })
}

export function useCreateProduct() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (input: ProductInput) =>
      send<Product>('/api/v1/products', { method: 'POST', body: JSON.stringify(input) }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin', 'products'] }),
  })
}

export function useUpdateProduct() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, input }: { id: string; input: ProductInput }) =>
      send<Product>(`/api/v1/products/${id}`, { method: 'PUT', body: JSON.stringify(input) }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin', 'products'] }),
  })
}

export function useDeleteProduct() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => send<void>(`/api/v1/products/${id}`, { method: 'DELETE' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin', 'products'] }),
  })
}

export function useAdminPayments(status: string, page = 0, size = 20) {
  const statusParam = status === 'ALL' ? '' : `&status=${status}`
  return useQuery({
    queryKey: adminKeys.payments(status, page),
    queryFn: () =>
      send<PaymentPage>(`/v1/payments?page=${page}&page_size=${size}${statusParam}`),
  })
}

export function useRefundPayment() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ paymentId, reason }: { paymentId: string; reason?: string }) =>
      send<{ payment: Payment }>(`/v1/payments/${paymentId}/refund`, {
        method: 'POST',
        body: JSON.stringify({ idempotency_key: `admin-${paymentId}-${Date.now()}`, reason: reason ?? 'Refund' }),
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin', 'payments'] }),
  })
}

export function usePayment(id: string) {
  return useQuery({
    queryKey: ['admin', 'payment', id],
    queryFn: () => send<{ payment: Payment }>(`/v1/payments/${id}`).then((d) => d.payment),
    enabled: id.length > 0,
  })
}