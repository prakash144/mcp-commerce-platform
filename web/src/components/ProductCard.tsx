import { Link } from 'react-router-dom'
import { Plus, Star } from 'lucide-react'
import type { Product } from '../api/types'
import { formatMoney } from '../lib/utils'
import { useCart } from '../store/cart'
import { ProductArt } from './ProductArt'

function hash(str: string): number {
  let h = 0
  for (let i = 0; i < str.length; i++) {
    h = (h << 5) - h + str.charCodeAt(i)
    h |= 0
  }
  return Math.abs(h)
}

function ratingFor(id: string): { value: string; reviews: number } {
  const h = hash(id)
  const value = (3.5 + (h % 15) / 10).toFixed(1)
  const reviews = (h % 420) + 18
  return { value, reviews }
}

export function ProductCard({ product }: { product: Product }) {
  const addItem = useCart((s) => s.addItem)
  const outOfStock = product.stock <= 0
  const lowStock = !outOfStock && product.stock < 10
  const { value, reviews } = ratingFor(product.id)

  return (
    <Link
      to={`/products/${product.id}`}
      className="group flex flex-col overflow-hidden rounded-2xl border border-zinc-200 bg-white transition-all hover:-translate-y-0.5 hover:shadow-lg"
    >
      <div className="relative">
        <ProductArt product={product} className="aspect-square w-full" />
        {outOfStock && (
          <span className="absolute left-3 top-3 rounded-full bg-zinc-950/80 px-2.5 py-1 text-xs font-medium text-white">
            Out of stock
          </span>
        )}
      </div>
      <div className="flex flex-1 flex-col p-4">
        <div className="flex items-center gap-1.5">
          <Star className="h-3.5 w-3.5 fill-amber-400 text-amber-400" />
          <span className="text-sm font-medium text-zinc-900">{value}</span>
          <span className="text-xs text-zinc-400">({reviews})</span>
        </div>
        <h3 className="mt-1.5 line-clamp-2 text-sm font-medium text-zinc-900 group-hover:text-teal-800">
          {product.name}
        </h3>
        <p className="mt-2 text-base font-semibold text-zinc-900">
          {formatMoney(product.price)}
        </p>
        {lowStock && (
          <p className="mt-1 text-xs text-amber-700">Only {product.stock} left</p>
        )}
        <div className="mt-auto pt-3">
          <button
            type="button"
            disabled={outOfStock}
            onClick={(e) => {
              e.preventDefault()
              addItem(product)
            }}
            className="inline-flex w-full items-center justify-center gap-2 rounded-full border border-zinc-200 px-4 py-2 text-sm font-medium text-zinc-900 transition-colors hover:bg-zinc-950 hover:text-white disabled:pointer-events-none disabled:opacity-50"
          >
            <Plus className="h-4 w-4" />
            {outOfStock ? 'Sold out' : 'Add to cart'}
          </button>
        </div>
      </div>
    </Link>
  )
}