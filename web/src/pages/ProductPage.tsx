import { Link, useParams } from 'react-router-dom'
import { ChevronRight, Minus, Plus, RotateCcw, ShieldCheck, ShoppingBag, Truck } from 'lucide-react'
import { useState } from 'react'
import { useProduct } from '../api/products'
import { ProductArt } from '../components/ProductArt'
import { Badge } from '../components/ui/badge'
import { Button } from '../components/ui/button'
import { Skeleton } from '../components/ui/skeleton'
import { formatMoney } from '../lib/utils'
import { useCart } from '../store/cart'

export function ProductPage() {
  const { id = '' } = useParams()
  const { data: product, isLoading, isError, error } = useProduct(id)
  const addItem = useCart((s) => s.addItem)
  const [qty, setQty] = useState(1)

  if (isLoading) {
    return (
      <div className="mx-auto max-w-6xl px-4 py-10 sm:px-6">
        <div className="grid gap-8 md:grid-cols-2">
          <Skeleton className="aspect-square w-full" />
          <div className="space-y-4">
            <Skeleton className="h-8 w-3/4" />
            <Skeleton className="h-6 w-1/3" />
            <Skeleton className="h-24 w-full" />
          </div>
        </div>
      </div>
    )
  }

  if (isError || !product) {
    const notFound = error && 'status' in error && error.status === 404
    return (
      <div className="mx-auto max-w-6xl px-4 py-16 sm:px-6">
        <div className="text-center">
          <p className="text-5xl">🔍</p>
          <h1 className="mt-4 text-2xl font-semibold tracking-tight text-zinc-900">
            {notFound ? 'Product not found' : 'Could not load product'}
          </h1>
          <p className="mx-auto mt-2 max-w-md text-sm text-zinc-500">
            {notFound
              ? 'The product you are looking for may have been removed or the link is incorrect.'
              : error?.message}
          </p>
          <Button asChild variant="outline" className="mt-6">
            <Link to="/catalog">Back to catalog</Link>
          </Button>
        </div>
      </div>
    )
  }

  const outOfStock = product.stock <= 0
  const canBuy = qty <= product.stock

  return (
    <div className="mx-auto max-w-6xl px-4 py-10 sm:px-6">
      <nav
        aria-label="Breadcrumb"
        className="mb-6 flex items-center gap-1.5 text-sm text-zinc-500"
      >
        <Link to="/" className="hover:text-zinc-900">
          Home
        </Link>
        <ChevronRight className="h-3.5 w-3.5 text-zinc-300" />
        <Link to="/catalog" className="hover:text-zinc-900">
          The Collection
        </Link>
        <ChevronRight className="h-3.5 w-3.5 text-zinc-300" />
        <span className="truncate text-zinc-900">{product.name}</span>
      </nav>

      <div className="grid gap-8 md:grid-cols-2 md:gap-12">
        <ProductArt product={product} className="aspect-square w-full rounded-2xl" />

        <div>
          <div className="flex items-start justify-between gap-3">
            <h1 className="text-3xl font-semibold tracking-tight text-zinc-900">
              {product.name}
            </h1>
            {outOfStock ? (
              <Badge variant="destructive">Out of stock</Badge>
            ) : product.stock < 10 ? (
              <Badge variant="warning">Only {product.stock} left</Badge>
            ) : (
              <Badge variant="success">In stock</Badge>
            )}
          </div>
          <p className="mt-2 text-2xl font-medium text-zinc-900">
            {formatMoney(product.price)}
          </p>
          <p className="mt-4 leading-relaxed text-zinc-600">
            {product.description || 'No description available.'}
          </p>
          <dl className="mt-6 grid grid-cols-2 gap-4 rounded-2xl border border-zinc-200 bg-zinc-50 p-4 text-sm">
            <div>
              <dt className="text-zinc-500">SKU</dt>
              <dd className="mt-0.5 font-medium text-zinc-900">{product.sku}</dd>
            </div>
            <div>
              <dt className="text-zinc-500">Stock</dt>
              <dd className="mt-0.5 font-medium text-zinc-900">{product.stock}</dd>
            </div>
          </dl>

          <div className="mt-6 flex items-center gap-4">
            <div className="flex items-center rounded-full border border-zinc-200">
              <button
                type="button"
                aria-label="Decrease quantity"
                className="flex h-12 w-10 items-center justify-center text-zinc-600 hover:text-zinc-900"
                onClick={() => setQty((q) => Math.max(1, q - 1))}
              >
                <Minus className="h-4 w-4" />
              </button>
              <span className="w-10 text-center font-medium">{qty}</span>
              <button
                type="button"
                aria-label="Increase quantity"
                className="flex h-12 w-10 items-center justify-center text-zinc-600 hover:text-zinc-900"
                onClick={() => setQty((q) => Math.min(product.stock, q + 1))}
              >
                <Plus className="h-4 w-4" />
              </button>
            </div>
            <Button
              size="lg"
              className="flex-1"
              variant={outOfStock ? 'outline' : 'accent'}
              disabled={outOfStock || !canBuy}
              onClick={() => addItem(product, qty)}
            >
              <ShoppingBag className="h-4 w-4" />
              Add to cart — {formatMoney(product.price * qty)}
            </Button>
          </div>
          <div className="mt-8 grid gap-3 border-t border-zinc-100 pt-6 text-sm text-zinc-600 sm:grid-cols-3">
            <span className="flex items-center gap-2">
              <Truck className="h-4 w-4 text-teal-700" /> Free shipping over $50
            </span>
            <span className="flex items-center gap-2">
              <RotateCcw className="h-4 w-4 text-teal-700" /> 30-day returns
            </span>
            <span className="flex items-center gap-2">
              <ShieldCheck className="h-4 w-4 text-teal-700" /> Secure checkout
            </span>
          </div>
        </div>
      </div>
    </div>
  )
}