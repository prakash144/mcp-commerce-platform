import { ChevronLeft, ChevronRight, SearchX } from 'lucide-react'
import { useSearchParams } from 'react-router-dom'
import { useProducts, type SortKey } from '../api/products'
import { ErrorState, ProductGrid, ProductGridSkeleton } from '../components/ProductGrid'
import { Button } from '../components/ui/button'

const PAGE_SIZE = 12

const SORT_OPTIONS: { value: SortKey; label: string }[] = [
  { value: '', label: 'Relevance' },
  { value: 'price-asc', label: 'Price: Low to High' },
  { value: 'price-desc', label: 'Price: High to Low' },
]

function isSortKey(v: string | null): v is SortKey {
  return v === null || v === '' || v === 'price-asc' || v === 'price-desc'
}

export function CatalogPage() {
  const [params, setParams] = useSearchParams()
  const page = Math.max(0, Number(params.get('page') ?? 0))
  const rawSort = params.get('sort')
  const sort: SortKey = isSortKey(rawSort) ? rawSort ?? '' : ''
  const q = (params.get('q') ?? '').trim().toLowerCase()

  const { data, isLoading, isError, error } = useProducts(page, PAGE_SIZE, sort)

  const goTo = (target: number) => {
    if (target === page) return
    const next = new URLSearchParams(params)
    if (target === 0) next.delete('page')
    else next.set('page', String(target))
    setParams(next)
  }

  const setSort = (value: SortKey) => {
    const next = new URLSearchParams(params)
    if (value === '') next.delete('sort')
    else next.set('sort', value)
    next.delete('page')
    setParams(next)
  }

  const filtered = q
    ? data?.content.filter((p) =>
        (p.name + ' ' + (p.description ?? '')).toLowerCase().includes(q),
      )
    : data?.content

  return (
    <div className="mx-auto max-w-6xl px-4 py-10 sm:px-6">
      <div className="flex flex-wrap items-end justify-between gap-4 pb-8">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-zinc-900">
            {q ? `Results for "${q}"` : 'The Collection'}
          </h1>
          {data && (
            <p className="mt-1 text-sm text-zinc-500">
              {q ? filtered?.length ?? 0 : data.totalElements}{' '}
              {q ? 'matches on this page' : 'products'}
            </p>
          )}
        </div>
        {!q && (
          <label className="flex items-center gap-2 text-sm">
            <span className="text-zinc-500">Sort</span>
            <select
              value={sort}
              onChange={(e) => setSort(e.target.value as SortKey)}
              aria-label="Sort products"
              className="h-9 rounded-full border border-zinc-200 bg-white px-3 text-sm text-zinc-900 outline-none focus:border-teal-600 focus:ring-2 focus:ring-teal-600/20"
            >
              {SORT_OPTIONS.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </label>
        )}
      </div>

      {isLoading && <ProductGridSkeleton />}
      {isError && <ErrorState message={error.message} />}
      {data && (
        <>
          {!filtered || filtered.length === 0 ? (
            <div className="py-20 text-center">
              <SearchX className="mx-auto h-10 w-10 text-zinc-300" />
              <p className="mt-3 font-medium text-zinc-700">No products found</p>
              <p className="mt-1 text-sm text-zinc-500">
                Try a different search term or remove your filter.
              </p>
              {q && (
                <Button
                  asChild
                  variant="outline"
                  className="mt-5"
                >
                  <a href="/catalog">View all products</a>
                </Button>
              )}
            </div>
          ) : (
            <ProductGrid products={filtered} />
          )}

          {!q && data.totalPages > 1 && (
            <div className="flex items-center justify-center gap-4 pt-10">
              <Button
                variant="outline"
                size="sm"
                disabled={page === 0}
                onClick={() => goTo(page - 1)}
              >
                <ChevronLeft className="h-4 w-4" /> Previous
              </Button>
              <span className="text-sm text-zinc-500">
                Page {page + 1} of {data.totalPages}
              </span>
              <Button
                variant="outline"
                size="sm"
                disabled={page + 1 >= data.totalPages}
                onClick={() => goTo(page + 1)}
              >
                Next <ChevronRight className="h-4 w-4" />
              </Button>
            </div>
          )}
        </>
      )}
    </div>
  )
}