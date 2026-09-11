import { useState } from 'react'
import type { Product } from '../api/types'

const PALETTES = [
  'from-teal-500 to-cyan-600',
  'from-indigo-500 to-violet-600',
  'from-amber-500 to-orange-600',
  'from-rose-500 to-pink-600',
  'from-emerald-500 to-green-600',
  'from-sky-500 to-blue-600',
  'from-fuchsia-500 to-purple-600',
]

function hash(str: string): number {
  let h = 0
  for (let i = 0; i < str.length; i++) {
    h = (h << 5) - h + str.charCodeAt(i)
    h |= 0
  }
  return Math.abs(h)
}

export function ProductArt({ product, className }: { product: Product; className?: string }) {
  const [failed, setFailed] = useState(false)
  const palette = PALETTES[hash(product.id) % PALETTES.length]
  const initials = product.name
    .split(' ').slice(0, 2).map((w) => w[0]?.toUpperCase()).join('')

  return (
    <div
      aria-hidden
      className={`bg-gradient-to-br ${palette} relative flex items-center justify-center overflow-hidden ${className ?? ''}`}
    >
      {product.imageUrl && !failed ? (
        <img
          src={product.imageUrl}
          alt={product.name}
          loading="lazy"
          onError={() => setFailed(true)}
          className="absolute inset-0 h-full w-full object-cover"
        />
      ) : (
        <span className="text-4xl font-semibold tracking-wider text-white/90">
          {initials}
        </span>
      )}
    </div>
  )
}