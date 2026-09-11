import { useEffect, useState } from 'react'
import { Loader2, X } from 'lucide-react'
import type { ProductInput } from '../../api/admin'
import { Button } from '../../components/ui/button'
import { Input } from '../../components/ui/input'
import type { Product } from '../../api/types'

interface Props {
  open: boolean
  product?: Product | null
  submitting: boolean
  error?: string | null
  onClose: () => void
  onSubmit: (input: ProductInput) => void
}

const EMPTY: ProductInput = {
  name: '',
  sku: '',
  price: 0,
  stock: 0,
  description: '',
  imageUrl: '',
}

export function ProductFormModal({ open, product, submitting, error, onClose, onSubmit }: Props) {
  const [form, setForm] = useState<ProductInput>(EMPTY)

  useEffect(() => {
    if (open) {
      setForm(
        product
          ? {
              name: product.name,
              sku: product.sku,
              price: Number(product.price),
              stock: product.stock,
              description: product.description ?? '',
              imageUrl: product.imageUrl ?? '',
            }
          : EMPTY,
      )
    }
  }, [open, product])

  if (!open) return null

  const set = <K extends keyof ProductInput>(key: K, value: ProductInput[K]) =>
    setForm((f) => ({ ...f, [key]: value }))

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-zinc-950/40 p-4" role="dialog" aria-modal="true">
      <div className="w-full max-w-lg rounded-2xl bg-white shadow-xl">
        <div className="flex items-center justify-between border-b border-zinc-100 p-5">
          <h2 className="text-lg font-semibold text-zinc-900">
            {product ? 'Edit product' : 'New product'}
          </h2>
          <button type="button" onClick={onClose} aria-label="Close" className="rounded-full p-1 text-zinc-400 hover:bg-zinc-100 hover:text-zinc-900">
            <X className="h-5 w-5" />
          </button>
        </div>
        <form
          className="space-y-4 p-5"
          onSubmit={(e) => {
            e.preventDefault()
            onSubmit(form)
          }}
        >
          <div className="grid gap-4 sm:grid-cols-2">
            <label className="block text-sm font-medium text-zinc-700">
              Name
              <Input
                required
                value={form.name}
                onChange={(e) => set('name', e.target.value)}
                className="mt-1"
                placeholder="Product name"
              />
            </label>
            <label className="block text-sm font-medium text-zinc-700">
              SKU
              <Input
                required
                value={form.sku}
                onChange={(e) => set('sku', e.target.value)}
                className="mt-1"
                placeholder="e.g. AC-400"
              />
            </label>
            <label className="block text-sm font-medium text-zinc-700">
              Price (₹)
              <Input
                required
                type="number"
                min="0"
                step="1"
                value={form.price}
                onChange={(e) => set('price', Number(e.target.value))}
                className="mt-1"
              />
            </label>
            <label className="block text-sm font-medium text-zinc-700">
              Stock
              <Input
                required
                type="number"
                min="0"
                step="1"
                value={form.stock}
                onChange={(e) => set('stock', Number(e.target.value))}
                className="mt-1"
              />
            </label>
          </div>
          <label className="block text-sm font-medium text-zinc-700">
            Image URL
            <Input
              value={form.imageUrl}
              onChange={(e) => set('imageUrl', e.target.value)}
              className="mt-1"
              placeholder="https://…"
            />
          </label>
          <label className="block text-sm font-medium text-zinc-700">
            Description
            <textarea
              value={form.description}
              onChange={(e) => set('description', e.target.value)}
              className="mt-1 h-24 w-full rounded-xl border border-zinc-200 bg-white px-3 py-2 text-sm placeholder:text-zinc-400 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-teal-600"
              placeholder="Short product description"
            />
          </label>

          {error && <p className="rounded-xl bg-red-50 p-3 text-sm text-red-700">{error}</p>}

          <div className="flex justify-end gap-3 border-t border-zinc-100 pt-4">
            <Button type="button" variant="outline" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={submitting}>
              {submitting && <Loader2 className="h-4 w-4 animate-spin" />}
              {product ? 'Save changes' : 'Create product'}
            </Button>
          </div>
        </form>
      </div>
    </div>
  )
}