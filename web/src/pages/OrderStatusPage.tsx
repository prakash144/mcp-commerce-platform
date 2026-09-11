import { Link, useParams } from 'react-router-dom'
import { Loader2, PackageCheck, RefreshCw } from 'lucide-react'
import { useOrder } from '../api/orders'
import { Badge } from '../components/ui/badge'
import { Button } from '../components/ui/button'
import { formatDate, formatMoney } from '../lib/utils'

const STATUS_LABELS: Record<string, string> = {
  CONFIRMED: 'Confirmed',
  PENDING: 'Pending payment',
  CANCELLED: 'Cancelled',
}

export function OrderStatusPage() {
  const { id = '' } = useParams()
  const { data: order, isLoading, isError, refetch } = useOrder(id)

  if (isLoading) {
    return (
      <div className="mx-auto max-w-2xl px-4 py-16 text-center sm:px-6">
        <Loader2 className="mx-auto h-8 w-8 animate-spin text-teal-700" />
        <p className="mt-3 text-zinc-500">Looking up your order…</p>
      </div>
    )
  }

  if (isError || !order) {
    return (
      <div className="mx-auto max-w-2xl px-4 py-16 text-center sm:px-6">
        <p className="text-zinc-700">We couldn't find that order.</p>
        <Button asChild variant="outline" className="mt-4">
          <Link to="/catalog">Continue shopping</Link>
        </Button>
      </div>
    )
  }

  return (
    <div className="mx-auto max-w-2xl px-4 py-12 sm:px-6">
      <div className="rounded-2xl border border-teal-100 bg-teal-50 p-8 text-center">
        <PackageCheck className="mx-auto h-10 w-10 text-teal-700" />
        <h1 className="mt-3 text-2xl font-semibold text-zinc-900">Thank you</h1>
        <p className="mt-1 text-zinc-600">
          Your order has been placed and charged successfully.
        </p>
        <p className="mt-4 inline-flex items-center gap-2 rounded-full bg-white px-3 py-1 text-sm text-zinc-600">
          Order <span className="font-mono font-medium text-zinc-900">{order.id.slice(0, 8)}</span>
        </p>
      </div>

      <div className="mt-6 flex items-center justify-between">
        <div>
          <div className="flex items-center gap-2">
            <Badge variant={order.status === 'CONFIRMED' ? 'success' : 'default'}>
              {STATUS_LABELS[order.status] ?? order.status}
            </Badge>
          </div>
          <p className="mt-1 text-sm text-zinc-500">
            Placed {formatDate(order.createdAt)} · {order.currency}
          </p>
        </div>
        <Button variant="outline" size="sm" onClick={() => refetch()}>
          <RefreshCw className="h-3.5 w-3.5" /> Refresh
        </Button>
      </div>

      <div className="mt-4 divide-y divide-zinc-200 rounded-2xl border border-zinc-200">
        {order.items.map((item) => (
          <div key={item.id} className="flex items-center justify-between p-4">
            <div>
              <p className="font-medium text-zinc-900">{item.productName}</p>
              <p className="text-sm text-zinc-500">
                {item.quantity} × {formatMoney(item.unitPrice, order.currency)}
              </p>
            </div>
            <p className="font-medium text-zinc-900">
              {formatMoney(item.lineTotal, order.currency)}
            </p>
          </div>
        ))}
      </div>

      <div className="mt-4 flex items-center justify-between rounded-2xl bg-zinc-950 p-6 text-white">
        <p className="text-sm text-zinc-400">Total charged</p>
        <p className="text-2xl font-semibold">
          {formatMoney(order.totalAmount, order.currency)}
        </p>
      </div>

      <div className="mt-8 text-center">
        <Button asChild variant="outline">
          <Link to="/catalog">Continue shopping</Link>
        </Button>
      </div>
    </div>
  )
}