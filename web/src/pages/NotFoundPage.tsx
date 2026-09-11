import { Link } from 'react-router-dom'
import { Compass } from 'lucide-react'
import { Button } from '../components/ui/button'

export function NotFoundPage() {
  return (
    <div className="mx-auto max-w-2xl px-4 py-24 text-center sm:px-6">
      <Compass className="mx-auto h-12 w-12 text-zinc-300" />
      <h1 className="mt-4 text-3xl font-semibold text-zinc-900">Page not found</h1>
      <p className="mt-2 text-zinc-500">The page you're looking for doesn't exist or moved.</p>
      <Button asChild className="mt-6">
        <Link to="/">Back home</Link>
      </Button>
    </div>
  )
}