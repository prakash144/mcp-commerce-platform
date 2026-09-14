import { Link } from 'react-router-dom'
import { Minus, Plus, RotateCcw, ShieldCheck, ShoppingBag, Trash2, Truck } from 'lucide-react'
import { ProductArt } from '../components/ProductArt'
import { Button } from '../components/ui/button'
import { formatMoney } from '../lib/utils'
import { sendEvent } from '../lib/trace'
import { useCart } from '../store/cart'

export function CartPage() {
  const { items, setQuantity, removeItem, total, count } = useCart()

  if (items.length === 0) {
    return (
      <div className="mx-auto max-w-3xl px-4 py-24 text-center sm:px-6">
        <ShoppingBag className="mx-auto h-12 w-12 text-zinc-300" />
        <h1 className="mt-4 text-2xl font-semibold text-zinc-900">Your cart is empty</h1>
        <p className="mt-2 text-zinc-500">Explore the collection and find something you love.</p>
        <Button asChild variant="accent" size="lg" className="mt-6">
          <Link to="/catalog">Start shopping</Link>
        </Button>
      </div>
    )
  }

  return (
    <div className="mx-auto max-w-3xl px-4 py-10 sm:px-6">
      <h1 className="text-2xl font-semibold tracking-tight text-zinc-900">
        Your cart{' '}
        <span className="text-base font-normal text-zinc-500">
          ({count()} item{count() === 1 ? '' : 's'})
        </span>
      </h1>

      <div className="mt-6 divide-y divide-zinc-200 rounded-2xl border border-zinc-200">
        {items.map(({ product, quantity }) => (
          <div key={product.id} className="flex gap-4 p-4 sm:p-5">
            <Link to={`/products/${product.id}`}>
              <ProductArt product={product} className="h-20 w-20 rounded-xl" />
            </Link>
            <div className="flex flex-1 flex-col">
              <div className="flex items-start justify-between gap-3">
                <Link
                  to={`/products/${product.id}`}
                  className="font-medium text-zinc-900 hover:text-teal-800"
                >
                  {product.name}
                </Link>
                <button
                  type="button"
                  aria-label={`Remove ${product.name} from cart`}
                  className="text-zinc-400 hover:text-red-600"
                  onClick={() => removeItem(product.id)}
                >
                  <Trash2 className="h-4 w-4" />
                </button>
              </div>
              <p className="text-sm text-zinc-500">
                {formatMoney(product.price)} each
              </p>
              <div className="mt-3 flex items-center justify-between">
                <div className="flex items-center rounded-full border border-zinc-200">
                  <button
                    type="button"
                    aria-label="Decrease quantity"
                    className="flex h-8 w-8 items-center justify-center text-zinc-600 hover:text-zinc-900"
                    onClick={() => setQuantity(product.id, quantity - 1)}
                  >
                    <Minus className="h-3.5 w-3.5" />
                  </button>
                  <span className="w-8 text-center text-sm font-medium">{quantity}</span>
                  <button
                    type="button"
                    aria-label="Increase quantity"
                    className="flex h-8 w-8 items-center justify-center text-zinc-600 hover:text-zinc-900"
                    onClick={() => setQuantity(product.id, quantity + 1)}
                  >
                    <Plus className="h-3.5 w-3.5" />
                  </button>
                </div>
                <p className="font-medium text-zinc-900">
                  {formatMoney(product.price * quantity)}
                </p>
              </div>
            </div>
          </div>
        ))}
      </div>

      <div className="mt-6 flex items-center justify-between rounded-2xl bg-zinc-950 p-6 text-white">
        <div>
          <p className="text-sm text-zinc-400">Order total</p>
          <p className="text-2xl font-semibold">{formatMoney(total())}</p>
          <div className="mt-3 flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-zinc-400">
            <span className="flex items-center gap-1.5">
              <ShieldCheck className="h-3.5 w-3.5" /> Secure checkout
            </span>
            <span className="flex items-center gap-1.5">
              <Truck className="h-3.5 w-3.5" /> Free shipping across India
            </span>
            <span className="flex items-center gap-1.5">
              <RotateCcw className="h-3.5 w-3.5" /> 7-day returns
            </span>
          </div>
        </div>
        <Button
          asChild
          variant="accent"
          size="lg"
          onClick={() => sendEvent('cart.checkout', { itemCount: count(), total: total() })}
        >
          <Link to="/checkout">Checkout</Link>
        </Button>
      </div>
    </div>
  )
}