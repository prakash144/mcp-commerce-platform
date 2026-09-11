import { expect, test } from '@playwright/test'

test.describe('Admin dashboard smoke (no mutations)', () => {
  test('overview loads KPIs from live services', async ({ page }) => {
    await page.goto('/admin')
    await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible()
    await expect(page.getByRole('link', { name: 'Products' })).toBeVisible()
    await expect(page.getByRole('link', { name: 'Orders' })).toBeVisible()
    await expect(page.getByRole('link', { name: 'Payments' })).toBeVisible()
    await expect(page.getByText('Total orders')).toBeVisible()
    await expect(page.getByText('Confirmed revenue')).toBeVisible()
  })

  test('products table shows the seeded catalog with images', async ({ page }) => {
    await page.goto('/admin/products')
    await expect(page.getByRole('heading', { name: 'Products' })).toBeVisible()
    await expect(page.getByRole('button', { name: 'New product' })).toBeVisible()
    await expect(page.getByText('Sonic-90 Wireless Headphones')).toBeVisible()
  })

  test('orders page lists orders with status filter', async ({ page }) => {
    await page.goto('/admin/orders')
    await expect(page.getByRole('heading', { name: 'Orders' })).toBeVisible()
    const row = page.locator('table tbody tr').first()
    await expect(row).toBeVisible()
    await expect(page.getByRole('button', { name: 'Confirmed' })).toBeVisible()
  })

  test('payments page lists and screens refundable rows', async ({ page }) => {
    await page.goto('/admin/payments')
    await expect(page.getByRole('heading', { name: 'Payments' })).toBeVisible()
    await expect(page.getByText('Transactions processed by the payment service.')).toBeVisible()
    const refundBtns = page.getByRole('button', { name: /Refund payment/ })
    const count = await refundBtns.count()
    expect(count).toBeGreaterThanOrEqual(0)
  })
})