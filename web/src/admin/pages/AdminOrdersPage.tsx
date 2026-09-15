import { useState } from 'react'
import { Loader2, X } from 'lucide-react'
import { useAdminOrders, useCancelOrder } from '../../api/admin'
import { Button } from '../../components/ui/button'
import { Card } from '../../components/ui/card'
import { Skeleton } from '../../components/ui/skeleton'
import type { Order } from '../../api/types'
import { formatDate, formatMoney } from '../../lib/utils'
import { OrderStatusBadge } from '../components/StatusBadge'

const FILTERS = ['ALL', 'PENDING', 'CONFIRMED', 'CANCELLED', 'FAILED'] as const
const PAGE_SIZE = 20

function OrderDetailModal({ order, onClose }: { order: Order; onClose: () => void }) {
  const cancelOrder = useCancelOrder()
  const [error, setError] = useState<string | null>(null)

  const cancel = async () => {
    setError(null)
    try {
      await cancelOrder.mutateAsync(order.id)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not cancel order.')
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-zinc-950/40 p-4" role="dialog" aria-modal="true">
      <div className="flex max-h-[85vh] w-full max-w-lg flex-col rounded-2xl bg-white shadow-xl">
        <div className="flex items-center justify-between border-b border-zinc-100 p-5">
          <div>
            <h2 className="text-lg font-semibold text-zinc-900">Order {order.id.slice(0, 8)}…</h2>
            <p className="mt-0.5 font-mono text-xs text-zinc-400">{order.id}</p>
          </div>
          <button type="button" onClick={onClose} aria-label="Close" className="rounded-full p-1 text-zinc-400 hover:bg-zinc-100 hover:text-zinc-900">
            <X className="h-5 w-5" />
          </button>
        </div>
        <div className="flex-1 overflow-y-auto p-5">
          <div className="flex flex-wrap items-center gap-2 text-sm">
            <OrderStatusBadge status={order.status} />
            <span className="text-zinc-500">{formatDate(order.createdAt)}</span>
          </div>
          <dl className="mt-4 grid grid-cols-2 gap-4 rounded-xl bg-zinc-50 p-4 text-sm">
            <div>
              <dt className="text-zinc-500">Customer</dt>
              <dd className="mt-0.5 font-mono text-xs text-zinc-900">{order.customerId}</dd>
            </div>
            <div>
              <dt className="text-zinc-500">Total</dt>
              <dd className="mt-0.5 font-medium text-zinc-900">
                {formatMoney(Number(order.totalAmount), order.currency)}
              </dd>
            </div>
          </dl>
          <div className="mt-4 divide-y divide-zinc-100 rounded-xl border border-zinc-200">
            {order.items.map((item) => (
              <div key={item.id} className="flex items-center justify-between gap-3 p-3 text-sm">
                <div className="min-w-0">
                  <p className="truncate font-medium text-zinc-900">{item.productName}</p>
                  <p className="text-xs text-zinc-500">
                    {item.quantity} × {formatMoney(Number(item.unitPrice), order.currency)}
                  </p>
                </div>
                <p className="font-medium text-zinc-900">
                  {formatMoney(Number(item.lineTotal), order.currency)}
                </p>
              </div>
            ))}
          </div>

          {error && <p className="mt-4 rounded-xl bg-red-50 p-3 text-sm text-red-700">{error}</p>}
        </div>
        {order.status === 'PENDING' && (
          <div className="border-t border-zinc-100 p-4">
            <Button
              variant="destructive"
              className="w-full"
              disabled={cancelOrder.isPending}
              onClick={cancel}
            >
              {cancelOrder.isPending && <Loader2 className="h-4 w-4 animate-spin" />}
              Cancel order
            </Button>
          </div>
        )}
      </div>
    </div>
  )
}

export function AdminOrdersPage() {
  const [status, setStatus] = useState<(typeof FILTERS)[number]>('ALL')
  const [page, setPage] = useState(0)
  const [selected, setSelected] = useState<Order | null>(null)
  const { data, isLoading } = useAdminOrders(status, page, PAGE_SIZE)

  return (
    <div>
      <header>
        <h1 className="text-2xl font-semibold tracking-tight text-zinc-900">Orders</h1>
        <p className="mt-1 text-sm text-zinc-500">Track, inspect and manage store orders.</p>
      </header>

      <div className="mt-4 flex gap-2">
        {FILTERS.map((f) => (
          <button
            key={f}
            type="button"
            onClick={() => {
              setStatus(f)
              setPage(0)
            }}
            className={`rounded-full border px-3.5 py-1.5 text-sm font-medium transition-colors ${
              status === f
                ? 'border-teal-700 bg-teal-700 text-white'
                : 'border-zinc-200 text-zinc-700 hover:border-zinc-900'
            }`}
          >
            {f === 'ALL' ? 'All' : f[0] + f.slice(1).toLowerCase()}
          </button>
        ))}
      </div>

      <Card className="mt-4 overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-zinc-100 text-left text-xs uppercase tracking-wide text-zinc-400">
                <th className="px-4 py-3 font-medium">Order</th>
                <th className="px-4 py-3 font-medium">Status</th>
                <th className="px-4 py-3 font-medium">Amount</th>
                <th className="px-4 py-3 font-medium">Customer</th>
                <th className="px-4 py-3 font-medium">Date</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-zinc-100">
              {isLoading &&
                Array.from({ length: 5 }).map((_, i) => (
                  <tr key={i}>
                    <td colSpan={5} className="px-4 py-3">
                      <Skeleton className="h-10 w-full" />
                    </td>
                  </tr>
                ))}
              {data?.orders.map((o) => (
                <tr
                  key={o.id}
                  className="cursor-pointer hover:bg-zinc-50"
                  onClick={() => setSelected(o)}
                >
                  <td className="px-4 py-3 font-mono text-xs text-zinc-500">{o.id.slice(0, 8)}…</td>
                  <td className="px-4 py-3">
                    <OrderStatusBadge status={o.status} />
                  </td>
                  <td className="px-4 py-3 font-medium text-zinc-900">
                    {formatMoney(Number(o.totalAmount), o.currency)}
                  </td>
                  <td className="px-4 py-3 font-mono text-xs text-zinc-500">{o.customerId.slice(0, 8)}…</td>
                  <td className="px-4 py-3 text-zinc-500">{formatDate(o.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {data && data.orders.length === 0 && (
          <p className="p-8 text-center text-sm text-zinc-400">No orders in this view.</p>
        )}
        {data && Math.ceil(data.totalCount / PAGE_SIZE) > 1 && (
          <div className="flex items-center justify-between border-t border-zinc-100 p-4">
            <p className="text-sm text-zinc-500">
              Page {page + 1} of {Math.ceil(data.totalCount / PAGE_SIZE)} · {data.totalCount} orders
            </p>
            <div className="flex gap-2">
              <Button variant="outline" size="sm" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
                Previous
              </Button>
              <Button
                variant="outline"
                size="sm"
                disabled={page + 1 >= Math.ceil(data.totalCount / PAGE_SIZE)}
                onClick={() => setPage((p) => p + 1)}
              >
                Next
              </Button>
            </div>
          </div>
        )}
      </Card>

      {selected && <OrderDetailModal order={selected} onClose={() => setSelected(null)} />}
    </div>
  )
}