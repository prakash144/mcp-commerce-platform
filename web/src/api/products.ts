import { useQuery } from '@tanstack/react-query'
import type { Product, ProductPage } from './types'

export type SortKey = '' | 'price-asc' | 'price-desc'

export const SORT_QUERY_PARAMS: Record<Exclude<SortKey, ''>, string> = {
  'price-asc': 'price,asc',
  'price-desc': 'price,desc',
}

export class ApiError extends Error {
  status: number
  constructor(status: number, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

async function get<T>(path: string): Promise<T> {
  const res = await fetch(path)
  if (!res.ok) {
    const message =
      res.status === 404
        ? 'The requested resource was not found.'
        : `Request failed (${res.status}).`
    throw new ApiError(res.status, message)
  }
  return res.json() as Promise<T>
}

export const productKeys = {
  all: ['products'] as const,
  page: (page: number, sort: SortKey) =>
    [...productKeys.all, 'page', page, 'sort', sort] as const,
  detail: (id: string) => [...productKeys.all, 'detail', id] as const,
}

export function useProducts(page = 0, size = 12, sort: SortKey = '') {
  const sortParam = sort === '' ? '' : `&sort=${SORT_QUERY_PARAMS[sort]}`
  return useQuery({
    queryKey: productKeys.page(page, sort),
    queryFn: () => get<ProductPage>(`/api/v1/products?page=${page}&size=${size}${sortParam}`),
    placeholderData: (prev) => prev,
  })
}

export function useProduct(id: string) {
  return useQuery({
    queryKey: productKeys.detail(id),
    queryFn: () => get<Product>(`/api/v1/products/${id}`),
    enabled: id.length > 0,
  })
}