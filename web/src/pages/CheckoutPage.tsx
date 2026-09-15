import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { CreditCard, Loader2, Lock, ShieldCheck } from 'lucide-react'
import { useCreateOrder } from '../api/orders'
import { ProductArt } from '../components/ProductArt'
import { Button } from '../components/ui/button'
import { formatMoney } from '../lib/utils'
import { sendEvent } from '../lib/trace'
import { useCart } from '../store/cart'

export function CheckoutPage() {
  const { items, total, clear, count } = useCart()
  const createOrder = useCreateOrder()
  const navigate = useNavigate()
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (count() === 0 && !createOrder.isSuccess) {
      navigate('/catalog', { replace: true })
    }
  }, [count, createOrder.isSuccess, navigate])

  if (items.length === 0) return null

  const placeOrder = async () => {
    setError(null)
    sendEvent('checkout.request', { itemCount: count(), total: total() })
    try {
      const order = await createOrder.mutateAsync({
        items: items.map(({ product, quantity }) => ({
          productId: product.id,
          quantity,
        })),
      })
      clear()
      sendEvent('order.placed', { orderId: order.id, status: order.status })
      navigate(`/orders/${order.id}`, { replace: true })
    } catch (err) {
      const e = err as Error & { code?: string }
      const code = e.code ?? e.message
      const messages: Record<string, string> = {
        EMPTY_ORDER: 'Your cart is empty.',
        INVALID_ITEM: 'One of the items in your cart is invalid.',
        INVALID_QUANTITY: 'Item quantity is out of range.',
        INVALID_CURRENCY: 'Invalid currency.',
        PAYMENT_UNAVAILABLE: 'Payments are temporarily unavailable. Please try again in a moment.',
        PAYMENT_REJECTED: 'Your payment was rejected by the payment provider.',
      }
      sendEvent('order.failed', { code })
      setError(messages[code] ?? `Could not place your order. Please try again.`)
    }
  }

  return (
    <div className="mx-auto max-w-6xl px-4 py-10 sm:px-6">
      <h1 className="text-2xl font-semibold tracking-tight text-zinc-900">Checkout</h1>

      <div className="mt-6 grid gap-8 lg:grid-cols-[1fr_380px]">
        <section>
          <div className="flex items-end justify-between pb-4">
            <h2 className="font-medium text-zinc-900">Review your items</h2>
            <Link to="/cart" className="text-sm font-medium text-teal-700 hover:text-teal-800">
              Edit cart
            </Link>
          </div>
          <div className="divide-y divide-zinc-200 rounded-2xl border border-zinc-200">
            {items.map(({ product, quantity }) => (
              <div key={product.id} className="flex gap-4 p-4">
                <ProductArt product={product} className="h-16 w-16 rounded-xl" />
                <div className="flex flex-1 items-center justify-between gap-3">
                  <div>
                    <p className="font-medium text-zinc-900">{product.name}</p>
                    <p className="text-sm text-zinc-500">
                      {quantity} × {formatMoney(product.price)}
                    </p>
                  </div>
                  <p className="font-medium text-zinc-900">
                    {formatMoney(product.price * quantity)}
                  </p>
                </div>
              </div>
            ))}
          </div>

          <div className="mt-6 flex items-start gap-3 rounded-2xl border border-blue-100 bg-blue-50 p-4 text-sm text-blue-900">
            <ShieldCheck className="mt-0.5 h-5 w-5 shrink-0" />
            <p>
              Payment happens securely server-side: placing the order calls the GraphQL
              API, which charges the payment service (gRPC) with an idempotency key and a
              state-machine guard. No card details are handled in the browser.
            </p>
          </div>
        </section>

        <aside>
          <div className="rounded-2xl border border-zinc-200 bg-zinc-50 p-6">
            <h2 className="font-medium text-zinc-900">Order summary</h2>
            <dl className="mt-4 space-y-2 text-sm">
              <div className="flex justify-between">
                <dt className="text-zinc-500">Subtotal</dt>
                <dd className="font-medium text-zinc-900">{formatMoney(total())}</dd>
              </div>
              <div className="flex justify-between">
                <dt className="text-zinc-500">Shipping</dt>
                <dd className="font-medium text-zinc-900">Free</dd>
              </div>
              <div className="flex justify-between border-t border-zinc-200 pt-3">
                <dt className="font-semibold text-zinc-900">Total</dt>
                <dd className="font-semibold text-zinc-900">{formatMoney(total())}</dd>
              </div>
            </dl>

            <div className="mt-5 flex items-center gap-2 rounded-xl bg-white p-3 text-xs text-zinc-500">
              <CreditCard className="h-4 w-4" />
              Payment handled server-side via the order and payment services
            </div>

            {error && (
              <p className="mt-4 rounded-xl bg-red-50 p-3 text-sm text-red-700">{error}</p>
            )}

            <Button
              size="lg"
              variant="accent"
              className="mt-5 w-full"
              disabled={createOrder.isPending}
              onClick={placeOrder}
            >
              {createOrder.isPending ? (
                <>
                  <Loader2 className="h-4 w-4 animate-spin" /> Processing payment…
                </>
              ) : (
                <>
                  <Lock className="h-4 w-4" /> Place order
                </>
              )}
            </Button>
          </div>
        </aside>
      </div>
    </div>
  )
}