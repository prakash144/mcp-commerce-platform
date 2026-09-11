import { Link, Outlet, useNavigate } from 'react-router-dom'
import { Search, ShoppingBag, Zap } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { useCart } from '../store/cart'

function CartButton() {
  const count = useCart((s) => s.count())
  return (
    <Link
      to="/cart"
      className="relative inline-flex h-10 items-center gap-1.5 rounded-full px-3 text-sm font-medium text-zinc-700 transition-colors hover:bg-zinc-100"
      aria-label={`Cart with ${count} item${count === 1 ? '' : 's'}`}
    >
      <ShoppingBag className="h-5 w-5" />
      <span className="hidden sm:inline">Cart</span>
      {count > 0 && (
        <span className="flex h-5 min-w-5 items-center justify-center rounded-full bg-teal-700 px-1 text-xs font-semibold text-white">
          {count}
        </span>
      )}
    </Link>
  )
}

function SearchBar({ className }: { className?: string }) {
  const navigate = useNavigate()
  const [query, setQuery] = useState('')
  const submit = (e: FormEvent) => {
    e.preventDefault()
    const q = query.trim()
    navigate(q ? `/catalog?q=${encodeURIComponent(q)}` : '/catalog')
  }
  return (
    <form
      role="search"
      onSubmit={submit}
      className={`relative ${className ?? ''}`}
      aria-label="Search products"
    >
      <Search className="pointer-events-none absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-zinc-400" />
      <input
        type="search"
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        placeholder="Search for products…"
        aria-label="Search products"
        className="h-10 w-full rounded-full border border-zinc-200 bg-zinc-50 pl-10 pr-4 text-sm outline-none transition-colors focus:border-teal-600 focus:bg-white focus:ring-2 focus:ring-teal-600/20"
      />
    </form>
  )
}

export function Layout() {
  return (
    <div className="flex min-h-screen flex-col bg-white">
      <p className="bg-zinc-950 px-4 py-2 text-center text-xs font-medium tracking-wide text-white">
        Free shipping on orders over $50 · Demo storefront on REST + GraphQL + gRPC
      </p>
      <header className="sticky top-0 z-40 border-b border-zinc-100 bg-white/80 backdrop-blur">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-x-4 gap-y-2 px-4 py-3 sm:px-6">
          <Link to="/" className="flex items-center gap-2 text-lg font-semibold tracking-tight text-zinc-900">
            <span className="flex h-8 w-8 items-center justify-center rounded-full bg-zinc-950 text-white">
              <Zap className="h-4 w-4" />
            </span>
            Lumen <span className="text-teal-700">&amp;</span> Co.
          </Link>
          <SearchBar className="order-3 w-full md:order-none md:w-auto md:flex-1" />
          <nav className="ml-auto flex items-center gap-1 sm:gap-2">
            <Link
              to="/catalog"
              className="rounded-full px-3 py-2 text-sm font-medium text-zinc-700 transition-colors hover:bg-zinc-100 sm:px-4"
            >
              Shop
            </Link>
            <CartButton />
          </nav>
        </div>
      </header>
      <main className="flex-1">
        <Outlet />
      </main>
      <footer className="border-t border-zinc-100">
        <div className="mx-auto grid max-w-6xl gap-8 px-4 py-12 sm:px-6 md:grid-cols-3">
          <div>
            <p className="flex items-center gap-2 font-semibold text-zinc-900">
              <span className="flex h-7 w-7 items-center justify-center rounded-full bg-zinc-950 text-white">
                <Zap className="h-3.5 w-3.5" />
              </span>
              Lumen <span className="text-teal-700">&amp;</span> Co.
            </p>
            <p className="mt-3 max-w-xs text-sm leading-relaxed text-zinc-500">
              A premium storefront on a real microservices commerce platform — REST,
              GraphQL, gRPC and (soon) event-driven Kafka.
            </p>
          </div>
          <div>
            <p className="text-sm font-semibold text-zinc-900">Shop</p>
            <ul className="mt-3 space-y-2 text-sm text-zinc-500">
              <li>
                <Link to="/catalog" className="hover:text-zinc-900">All products</Link>
              </li>
              <li>
                <Link to="/catalog?q=keyboard" className="hover:text-zinc-900">Keyboards</Link>
              </li>
              <li>
                <Link to="/catalog?q=mouse" className="hover:text-zinc-900">Mice</Link>
              </li>
            </ul>
          </div>
          <div>
            <p className="text-sm font-semibold text-zinc-900">Why us</p>
            <ul className="mt-3 space-y-2 text-sm text-zinc-500">
              <li>Free shipping over $50</li>
              <li>30-day easy returns</li>
              <li>Secure payment via integrated gateways</li>
            </ul>
          </div>
        </div>
        <div className="border-t border-zinc-100 py-6 text-center text-xs text-zinc-400">
          © {new Date().getFullYear()} Lumen &amp; Co. — demo storefront
        </div>
      </footer>
    </div>
  )
}