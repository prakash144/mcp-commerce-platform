import { useState } from 'react'
import { Pencil, Plus, Trash2 } from 'lucide-react'
import {
  useAdminProducts,
  useCreateProduct,
  useDeleteProduct,
  useUpdateProduct,
  type ProductInput,
} from '../../api/admin'
import { Badge } from '../../components/ui/badge'
import { Button } from '../../components/ui/button'
import { Card } from '../../components/ui/card'
import { Skeleton } from '../../components/ui/skeleton'
import type { Product } from '../../api/types'
import { formatDate, formatMoney } from '../../lib/utils'
import { ProductFormModal } from '../components/ProductFormModal'

const PAGE_SIZE = 20

export function AdminProductsPage() {
  const [page, setPage] = useState(0)
  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<Product | null>(null)
  const [error, setError] = useState<string | null>(null)

  const { data, isLoading } = useAdminProducts(page, PAGE_SIZE)
  const createProduct = useCreateProduct()
  const updateProduct = useUpdateProduct()
  const deleteProduct = useDeleteProduct()

  const openCreate = () => {
    setEditing(null)
    setError(null)
    setModalOpen(true)
  }
  const openEdit = (p: Product) => {
    setEditing(p)
    setError(null)
    setModalOpen(true)
  }

  const submit = async (input: ProductInput) => {
    setError(null)
    try {
      if (editing) await updateProduct.mutateAsync({ id: editing.id, input })
      else await createProduct.mutateAsync(input)
      setModalOpen(false)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not save product.')
    }
  }

  const remove = async (p: Product) => {
    if (!window.confirm(`Delete "${p.name}"? This cannot be undone.`)) return
    try {
      await deleteProduct.mutateAsync(p.id)
    } catch (err) {
      window.alert(err instanceof Error ? err.message : 'Could not delete product.')
    }
  }

  return (
    <div>
      <header className="flex items-end justify-between">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-zinc-900">Products</h1>
          <p className="mt-1 text-sm text-zinc-500">Manage the catalog served to the storefront.</p>
        </div>
        <Button onClick={openCreate}>
          <Plus className="h-4 w-4" /> New product
        </Button>
      </header>

      <Card className="mt-6 overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-zinc-100 text-left text-xs uppercase tracking-wide text-zinc-400">
                <th className="px-4 py-3 font-medium">Product</th>
                <th className="px-4 py-3 font-medium">SKU</th>
                <th className="px-4 py-3 font-medium">Price</th>
                <th className="px-4 py-3 font-medium">Stock</th>
                <th className="px-4 py-3 font-medium">Updated</th>
                <th className="px-4 py-3 text-right font-medium">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-zinc-100">
              {isLoading &&
                Array.from({ length: 6 }).map((_, i) => (
                  <tr key={i}>
                    <td colSpan={6} className="px-4 py-3">
                      <Skeleton className="h-10 w-full" />
                    </td>
                  </tr>
                ))}
              {data?.content.map((p) => (
                <tr key={p.id} className="hover:bg-zinc-50">
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-3">
                      {p.imageUrl ? (
                        <img
                          src={p.imageUrl}
                          alt=""
                          loading="lazy"
                          className="h-10 w-10 rounded-lg object-cover"
                        />
                      ) : (
                        <span className="flex h-10 w-10 items-center justify-center rounded-lg bg-zinc-100 text-xs font-semibold text-zinc-500">
                          {p.name.slice(0, 2).toUpperCase()}
                        </span>
                      )}
                      <span className="max-w-[260px] truncate font-medium text-zinc-900">{p.name}</span>
                    </div>
                  </td>
                  <td className="px-4 py-3 font-mono text-xs text-zinc-500">{p.sku}</td>
                  <td className="px-4 py-3 font-medium text-zinc-900">{formatMoney(p.price)}</td>
                  <td className="px-4 py-3">
                    <Badge variant={p.stock <= 0 ? 'destructive' : p.stock < 10 ? 'warning' : 'success'}>
                      {p.stock <= 0 ? 'Out of stock' : p.stock < 10 ? `${p.stock} left` : p.stock}
                    </Badge>
                  </td>
                  <td className="px-4 py-3 text-zinc-500">{formatDate(p.updatedAt)}</td>
                  <td className="px-4 py-3">
                    <div className="flex justify-end gap-1">
                      <Button variant="outline" size="sm" onClick={() => openEdit(p)} aria-label={`Edit ${p.name}`}>
                        <Pencil className="h-3.5 w-3.5" />
                      </Button>
                      <Button
                        variant="outline"
                        size="sm"
                        className="text-red-600 hover:bg-red-50"
                        onClick={() => remove(p)}
                        aria-label={`Delete ${p.name}`}
                      >
                        <Trash2 className="h-3.5 w-3.5" />
                      </Button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {data && data.content.length === 0 && (
          <p className="p-8 text-center text-sm text-zinc-400">No products yet. Create your first one.</p>
        )}
        {data && data.totalPages > 1 && (
          <div className="flex items-center justify-between border-t border-zinc-100 p-4">
            <p className="text-sm text-zinc-500">
              Page {page + 1} of {data.totalPages} · {data.totalElements} products
            </p>
            <div className="flex gap-2">
              <Button variant="outline" size="sm" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
                Previous
              </Button>
              <Button
                variant="outline"
                size="sm"
                disabled={page + 1 >= data.totalPages}
                onClick={() => setPage((p) => p + 1)}
              >
                Next
              </Button>
            </div>
          </div>
        )}
      </Card>

      <ProductFormModal
        open={modalOpen}
        product={editing}
        submitting={createProduct.isPending || updateProduct.isPending}
        error={error}
        onClose={() => setModalOpen(false)}
        onSubmit={submit}
      />
    </div>
  )
}