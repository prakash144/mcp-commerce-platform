import { Link, NavLink, Outlet } from 'react-router-dom'
import { CreditCard, LayoutDashboard, Package, ShoppingCart, Store } from 'lucide-react'

const NAV = [
  { to: '/admin', label: 'Dashboard', icon: LayoutDashboard, end: true },
  { to: '/admin/products', label: 'Products', icon: Package },
  { to: '/admin/orders', label: 'Orders', icon: ShoppingCart },
  { to: '/admin/payments', label: 'Payments', icon: CreditCard },
]

export function AdminLayout() {
  return (
    <div className="flex min-h-screen bg-zinc-50">
      <aside className="sticky top-0 flex h-screen w-60 shrink-0 flex-col border-r border-zinc-200 bg-white">
        <Link to="/admin" className="flex items-center gap-2 px-5 py-5 text-lg font-semibold tracking-tight text-zinc-900">
          <span className="flex h-8 w-8 items-center justify-center rounded-full bg-teal-700 text-white">
            <Store className="h-4 w-4" />
          </span>
          Apna<span className="text-teal-700">Kart</span>
          <span className="mt-0.5 rounded-full bg-zinc-100 px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-zinc-500">
            Admin
          </span>
        </Link>
        <nav className="flex-1 space-y-1 px-3">
          {NAV.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.end}
              className={({ isActive }) =>
                `flex items-center gap-3 rounded-xl px-3 py-2 text-sm font-medium transition-colors ${
                  isActive
                    ? 'bg-teal-700 text-white'
                    : 'text-zinc-700 hover:bg-zinc-100'
                }`
              }
            >
              <item.icon className="h-4 w-4" />
              {item.label}
            </NavLink>
          ))}
        </nav>
        <div className="border-t border-zinc-100 p-4">
          <Link to="/" className="text-xs font-medium text-zinc-500 hover:text-zinc-900">
            ← Back to storefront
          </Link>
        </div>
      </aside>
      <main className="min-w-0 flex-1">
        <div className="mx-auto max-w-6xl px-8 py-8">
          <Outlet />
        </div>
      </main>
    </div>
  )
}