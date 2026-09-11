import { createBrowserRouter, RouterProvider } from 'react-router-dom'
import { AdminLayout } from './admin/AdminLayout'
import { AdminOrdersPage } from './admin/pages/AdminOrdersPage'
import { AdminPaymentsPage } from './admin/pages/AdminPaymentsPage'
import { AdminProductsPage } from './admin/pages/AdminProductsPage'
import { DashboardPage } from './admin/pages/DashboardPage'
import { Layout } from './components/Layout'
import { CatalogPage } from './pages/CatalogPage'
import { CartPage } from './pages/CartPage'
import { CheckoutPage } from './pages/CheckoutPage'
import { HomePage } from './pages/HomePage'
import { NotFoundPage } from './pages/NotFoundPage'
import { OrderStatusPage } from './pages/OrderStatusPage'
import { ProductPage } from './pages/ProductPage'

const router = createBrowserRouter([
  {
    path: '/',
    element: <Layout />,
    children: [
      { index: true, element: <HomePage /> },
      { path: 'catalog', element: <CatalogPage /> },
      { path: 'products/:id', element: <ProductPage /> },
      { path: 'cart', element: <CartPage /> },
      { path: 'checkout', element: <CheckoutPage /> },
      { path: 'orders/:id', element: <OrderStatusPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
  {
    path: '/admin',
    element: <AdminLayout />,
    children: [
      { index: true, element: <DashboardPage /> },
      { path: 'products', element: <AdminProductsPage /> },
      { path: 'orders', element: <AdminOrdersPage /> },
      { path: 'payments', element: <AdminPaymentsPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
])

export default function App() {
  return <RouterProvider router={router} />
}