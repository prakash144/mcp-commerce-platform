import { Link } from 'react-router-dom'
import { ArrowRight, RotateCcw, ShieldCheck, Truck } from 'lucide-react'
import { useProducts } from '../api/products'
import { ProductGrid, ProductGridSkeleton } from '../components/ProductGrid'
import { Button } from '../components/ui/button'

const CATEGORIES = [
  { label: 'Audio', q: 'earbuds' },
  { label: 'Sneakers', q: 'sneakers' },
  { label: 'Smartwatches', q: 'watch' },
  { label: 'Monitors', q: 'monitor' },
  { label: 'Cameras', q: 'camera' },
  { label: 'Bags', q: 'backpack' },
]

export function HomePage() {
  const { data, isLoading, isError, error } = useProducts(0, 8)

  return (
    <div className="mx-auto max-w-6xl px-4 pt-10 sm:px-6">
      <section className="mx-auto max-w-2xl pb-10 text-center">
        <h1 className="text-4xl font-semibold tracking-tight text-zinc-900 sm:text-5xl">
          Everyday essentials,
          <span className="mt-2 block text-teal-700">delivered across India.</span>
        </h1>
        <p className="mt-4 text-lg text-zinc-500">
          Electronics, footwear, audio and more — with free shipping, easy returns and
          secure payments on every order.
        </p>
        <div className="mt-6 flex justify-center gap-3">
          <Button asChild size="lg">
            <Link to="/catalog">
              Shop now <ArrowRight className="h-4 w-4" />
            </Link>
          </Button>
        </div>
      </section>

      <div className="flex flex-wrap justify-center gap-2 pb-12">
        {CATEGORIES.map((c) => (
          <Link
            key={c.q}
            to={`/catalog?q=${c.q}`}
            className="rounded-full border border-zinc-200 px-4 py-1.5 text-sm text-zinc-700 hover:border-zinc-900 hover:text-zinc-900"
          >
            {c.label}
          </Link>
        ))}
      </div>

      <div className="grid gap-4 pb-12 sm:grid-cols-3">
        <div className="flex items-center gap-3 rounded-2xl border border-zinc-200 p-4">
          <Truck className="h-6 w-6 shrink-0 text-teal-700" />
          <div>
            <p className="text-sm font-semibold text-zinc-900">Free shipping</p>
            <p className="text-xs text-zinc-500">On every order across India</p>
          </div>
        </div>
        <div className="flex items-center gap-3 rounded-2xl border border-zinc-200 p-4">
          <ShieldCheck className="h-6 w-6 shrink-0 text-teal-700" />
          <div>
            <p className="text-sm font-semibold text-zinc-900">Secure payments</p>
            <p className="text-xs text-zinc-500">Verified transaction every time</p>
          </div>
        </div>
        <div className="flex items-center gap-3 rounded-2xl border border-zinc-200 p-4">
          <RotateCcw className="h-6 w-6 shrink-0 text-teal-700" />
          <div>
            <p className="text-sm font-semibold text-zinc-900">Easy returns</p>
            <p className="text-xs text-zinc-500">7-day no-hassle exchange</p>
          </div>
        </div>
      </div>

      <section className="pb-16">
        <div className="flex items-end justify-between pb-6">
          <h2 className="text-2xl font-semibold tracking-tight text-zinc-900">Featured</h2>
          <Link to="/catalog" className="text-sm font-medium text-teal-700 hover:text-teal-800">
            View all
          </Link>
        </div>
        {isLoading && <ProductGridSkeleton />}
        {isError && (
          <p className="rounded-2xl border border-red-100 bg-red-50 p-6 text-sm text-red-700">
            Could not load products: {error.message}
          </p>
        )}
        {data && data.content.length > 0 && <ProductGrid products={data.content} />}
      </section>
    </div>
  )
}