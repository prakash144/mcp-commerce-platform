import { useState } from 'react'
import { RotateCcw } from 'lucide-react'
import { useAdminPayments, useRefundPayment } from '../../api/admin'
import { Button } from '../../components/ui/button'
import { Card } from '../../components/ui/card'
import { Skeleton } from '../../components/ui/skeleton'
import { formatDate, formatPriceMinor } from '../../lib/utils'
import type { Payment } from '../../api/types'
import { PaymentStatusBadge } from '../components/StatusBadge'

const FILTERS = ['ALL', 'CAPTURED', 'PENDING', 'AUTHORIZED', 'PARTIALLY_REFUNDED', 'REFUNDED', 'VOIDED', 'FAILED'] as const
const PAGE_SIZE = 20

const REFUNDABLE = new Set(['PAYMENT_STATUS_CAPTURED', 'PAYMENT_STATUS_PARTIALLY_REFUNDED'])

export function AdminPaymentsPage() {
  const [status, setStatus] = useState<(typeof FILTERS)[number]>('ALL')
  const [page, setPage] = useState(0)
  const { data, isLoading } = useAdminPayments(status === 'ALL' ? 'ALL' : `PAYMENT_STATUS_${status}`, page, PAGE_SIZE)
  const refundPayment = useRefundPayment()

  const refund = async (p: Payment) => {
    const reason = window.prompt(`Refund payment ${p.id.slice(0, 8)}…? Leave empty for full refund.\n\nReason (optional):`, '') ?? null
    if (reason === null) return
    const ok = window.confirm(`Confirm refund for ${formatPriceMinor(p.amountMinor, p.currency)} on ${p.id.slice(0, 8)}…?`)
    if (!ok) return
    try {
      await refundPayment.mutateAsync({ paymentId: p.id, reason: reason || undefined })
    } catch (err) {
      window.alert(err instanceof Error ? err.message : 'Could not refund payment.')
    }
  }

  const labelFor = (f: (typeof FILTERS)[number]) =>
    f === 'ALL' ? 'All' : f.toLowerCase().replace(/_/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase())

  return (
    <div>
      <header>
        <h1 className="text-2xl font-semibold tracking-tight text-zinc-900">Payments</h1>
        <p className="mt-1 text-sm text-zinc-500">Transactions processed by the payment service.</p>
      </header>

      <div className="mt-4 flex flex-wrap gap-2">
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
            {labelFor(f)}
          </button>
        ))}
      </div>

      <Card className="mt-4 overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-zinc-100 text-left text-xs uppercase tracking-wide text-zinc-400">
                <th className="px-4 py-3 font-medium">Payment</th>
                <th className="px-4 py-3 font-medium">Order</th>
                <th className="px-4 py-3 font-medium">Amount</th>
                <th className="px-4 py-3 font-medium">Method</th>
                <th className="px-4 py-3 font-medium">Status</th>
                <th className="px-4 py-3 font-medium">Date</th>
                <th className="px-4 py-3 text-right font-medium">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-zinc-100">
              {isLoading &&
                Array.from({ length: 5 }).map((_, i) => (
                  <tr key={i}>
                    <td colSpan={7} className="px-4 py-3">
                      <Skeleton className="h-10 w-full" />
                    </td>
                  </tr>
                ))}
              {data?.payments.map((p) => (
                <tr key={p.id} className="hover:bg-zinc-50">
                  <td className="px-4 py-3 font-mono text-xs text-zinc-500">{p.id.slice(0, 8)}…</td>
                  <td className="px-4 py-3 font-mono text-xs text-zinc-500">{p.orderId.slice(0, 8)}…</td>
                  <td className="px-4 py-3 font-medium text-zinc-900">
                    {formatPriceMinor(p.amountMinor, p.currency)}
                  </td>
                  <td className="px-4 py-3 text-zinc-500">
                    {p.method.replace(/^PAYMENT_METHOD_/, '').toLowerCase()}
                  </td>
                  <td className="px-4 py-3">
                    <PaymentStatusBadge status={p.status} />
                  </td>
                  <td className="px-4 py-3 text-zinc-500">{formatDate(p.createdAt)}</td>
                  <td className="px-4 py-3">
                    <div className="flex justify-end">
                      {REFUNDABLE.has(p.status) ? (
                        <Button
                          variant="outline"
                          size="sm"
                          disabled={refundPayment.isPending}
                          onClick={() => refund(p)}
                          aria-label={`Refund payment ${p.id.slice(0, 8)}`}
                        >
                          <RotateCcw className="h-3.5 w-3.5" /> Refund
                        </Button>
                      ) : (
                        <span className="text-xs text-zinc-300">—</span>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {data && data.payments.length === 0 && (
          <p className="p-8 text-center text-sm text-zinc-400">No payments in this view.</p>
        )}
        {data && Math.ceil(Number(data.totalCount) / PAGE_SIZE) > 1 && (
          <div className="flex items-center justify-between border-t border-zinc-100 p-4">
            <p className="text-sm text-zinc-500">
              Page {page + 1} of {Math.ceil(Number(data.totalCount) / PAGE_SIZE)} · {data.totalCount} payments
            </p>
            <div className="flex gap-2">
              <Button variant="outline" size="sm" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
                Previous
              </Button>
              <Button
                variant="outline"
                size="sm"
                disabled={page + 1 >= Math.ceil(Number(data.totalCount) / PAGE_SIZE)}
                onClick={() => setPage((p) => p + 1)}
              >
                Next
              </Button>
            </div>
          </div>
        )}
      </Card>
    </div>
  )
}