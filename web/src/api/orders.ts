import { useMutation, useQuery } from '@tanstack/react-query'
import type { Order } from './types'

interface CreateOrderInput {
  items: { productId: string; quantity: number }[]
  customerId?: string
  currency?: string
}

interface GraphQLResponse<T> {
  data?: T
  errors?: { message: string; extensions?: { code?: string } }[]
}

const endpoint = '/graphql'

async function gql<T>(query: string, variables: Record<string, unknown>): Promise<T> {
  const res = await fetch(endpoint, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
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
  if (body.data == null) {
    throw new Error('Unexpected empty GraphQL response')
  }
  return body.data
}

export function useCreateOrder() {
  return useMutation({
    mutationFn: (input: CreateOrderInput) =>
      gql<{ createOrder: Order }>(
        `mutation CreateOrder($input: CreateOrderInput!) {
          createOrder(input: $input) {
            id
            customerId
            status
            totalAmount
            currency
            createdAt
            items {
              id
              productId
              productName
              unitPrice
              quantity
              lineTotal
            }
          }
        }`,
        { input: { currency: 'INR', ...input } },
      ).then((d) => d.createOrder),
  })
}

export function useOrder(id: string) {
  return useQuery({
    queryKey: ['order', id],
    queryFn: () =>
      gql<{ order: Order | null }>(
        `query Order($id: ID!) {
          order(id: $id) {
            id
            customerId
            status
            totalAmount
            currency
            createdAt
            updatedAt
            items {
              id
              productId
              productName
              unitPrice
              quantity
              lineTotal
            }
          }
        }`,
        { id },
      ).then((d) => d.order),
    enabled: id.length > 0,
  })
}