import { Link } from 'react-router-dom'
import { CreditCard, IndianRupee, Package, ShoppingCart } from 'lucide-react'
import { useAdminOrders, useAdminPayments, useAdminProducts, useOrderStats } from '../../api/admin'
import { Badge } from '../../components/ui/badge'
import { Card, CardContent } from '../../components/ui/card'
import { Skeleton } from '../../components/ui/skeleton'
import { formatDate, formatMoney } from '../../lib/utils'
import { orderStatusVariant } from '../components/StatusBadge'

function StatCard({
  label,
  value,
  hint,
  icon: Icon,
  loading,
}: {
  label: string
  value: string
  hint: string
  icon: typeof Package
  loading?: boolean
}) {
  return (
    <Card>
      <CardContent className="p-5">
        <div className="flex items-start justify-between">
          <div>
            <p className="text-sm text-zinc-500">{label}</p>
            {loading ? (
              <Skeleton className="mt-2 h-8 w-24" />
            ) : (
              <p className="mt-1 text-2xl font-semibold tracking-tight text-zinc-900">{value}</p>
            )}
            <p className="mt-1 text-xs text-zinc-400">{hint}</p>
          </div>
          <span className="flex h-10 w-10 items-center justify-center rounded-xl bg-teal-50 text-teal-700">
            <Icon className="h-5 w-5" />
          </span>
        </div>
      </CardContent>
    </Card>
  )
}

export function DashboardPage() {
  const stats = useOrderStats()
  const products = useAdminProducts(0, 1)
  const payments = useAdminPayments('ALL', 0, 1)
  const recent = useAdminOrders('ALL', 0, 5)

  const statsData = stats.data
  const revenue = statsData ? formatMoney(statsData.revenue) : '—'

  return (
    <div>
      <header className="flex items-end justify-between">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-zinc-900">Dashboard</h1>
          <p className="mt-1 text-sm text-zinc-500">Store health at a glance across all services.</p>
        </div>
      </header>

      <div className="mt-6 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          label="Total orders"
          value={statsData ? String(statsData.totalOrders) : '—'}
          hint="Across all customers"
          icon={ShoppingCart}
          loading={stats.isLoading}
        />
        <StatCard
          label="Confirmed revenue"
          value={revenue}
          hint="Confirmed orders only"
          icon={IndianRupee}
          loading={stats.isLoading}
        />
        <StatCard
          label="Products"
          value={products.data ? String(products.data.totalElements) : '—'}
          hint="In the catalog"
          icon={Package}
          loading={products.isLoading}
        />
        <StatCard
          label="Payments"
          value={payments.data ? String(payments.data.totalCount) : '—'}
          hint="Total transactions"
          icon={CreditCard}
          loading={payments.isLoading}
        />
      </div>

      <Card className="mt-6">
        <div className="flex items-center justify-between p-6 pb-0">
          <h2 className="font-medium text-zinc-900">Recent orders</h2>
          <Link to="/admin/orders" className="text-sm font-medium text-teal-700 hover:text-teal-800">
            View all
          </Link>
        </div>
        {recent.isLoading ? (
          <div className="space-y-3 p-6">
            <Skeleton className="h-12 w-full" />
            <Skeleton className="h-12 w-full" />
            <Skeleton className="h-12 w-full" />
          </div>
        ) : (
          <div className="overflow-x-auto p-3">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-xs uppercase tracking-wide text-zinc-400">
                  <th className="px-3 py-2 font-medium">Order</th>
                  <th className="px-3 py-2 font-medium">Status</th>
                  <th className="px-3 py-2 font-medium">Amount</th>
                  <th className="px-3 py-2 font-medium">Date</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-zinc-100">
                {recent.data?.orders.map((o) => (
                  <tr key={o.id}>
                    <td className="px-3 py-3 font-mono text-xs text-zinc-500">{o.id.slice(0, 8)}…</td>
                    <td className="px-3 py-3">
                      <Badge variant={orderStatusVariant(o.status)}>{o.status}</Badge>
                    </td>
                    <td className="px-3 py-3 font-medium text-zinc-900">
                      {formatMoney(Number(o.totalAmount), o.currency)}
                    </td>
                    <td className="px-3 py-3 text-zinc-500">{formatDate(o.createdAt)}</td>
                  </tr>
                ))}
                {recent.data && recent.data.orders.length === 0 && (
                  <tr>
                    <td colSpan={4} className="px-3 py-8 text-center text-sm text-zinc-400">
                      No orders yet.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </div>
  )
}