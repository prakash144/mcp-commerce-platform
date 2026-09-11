import { expect, test } from '@playwright/test'

const SCREENSHOTS = 'e2e/screenshots'

test.describe('Storefront full journey', () => {
  test('browse → cart → checkout → order confirmed', async ({ page }) => {
    // Home: hero + featured products
    await page.goto('/')
    await expect(page.getByRole('heading', { name: /Considered goods/ })).toBeVisible()
    await expect(page.getByRole('link', { name: /Shop the collection/ })).toBeVisible()
    await page.screenshot({ path: `${SCREENSHOTS}/01-home.png`, fullPage: true })
    await expect(page).toHaveScreenshot('01-home.png', { fullPage: true })

    // Catalog: real products from product-service (REST via proxy)
    await page.getByRole('link', { name: 'Shop the collection' }).click()
    await expect(page.getByRole('heading', { name: 'The Collection' })).toBeVisible()
    const cards = page.locator('a[href^="/products/"]')
    await expect(cards.first()).toBeVisible()
    await expect(cards.first()).toContainText(/Add to cart/)
    await page.screenshot({ path: `${SCREENSHOTS}/02-catalog.png`, fullPage: true })
    await expect(page).toHaveScreenshot('02-catalog.png', { fullPage: true })

    // Product detail: add a couple to cart
    const productName = await cards.first().locator('h3').innerText()
    await cards.first().click({ position: { x: 60, y: 60 } })
    await page.waitForURL(/\/products\//)
    await expect(page.getByRole('heading', { name: productName })).toBeVisible()
    const addButton = page.getByRole('button', { name: /Add to cart —/ })
    await expect(addButton).toBeVisible()
    await expect(addButton).toBeEnabled()
    await page.screenshot({ path: `${SCREENSHOTS}/03-product.png`, fullPage: true })
    await expect(page).toHaveScreenshot('03-product.png', { fullPage: true })
    await addButton.click()

    // Cart: badge reflects the item count
    await expect(page.getByLabel(/Cart with 1 item/)).toBeVisible()
    await page.goto('/cart')
    await expect(page.getByText(productName, { exact: true })).toBeVisible()
    await expect(page.getByText('Order total')).toBeVisible()
    await page.screenshot({ path: `${SCREENSHOTS}/04-cart.png`, fullPage: true })
    await expect(page).toHaveScreenshot('04-cart.png', { fullPage: true })

    // Checkout: place the order via GraphQL createOrder
    await page.getByRole('link', { name: 'Checkout' }).click()
    await expect(page.getByRole('heading', { name: 'Checkout' })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Place order' })).toBeVisible()
    await page.screenshot({ path: `${SCREENSHOTS}/05-checkout.png`, fullPage: true })
    await expect(page).toHaveScreenshot('05-checkout.png', { fullPage: true })
    await page.getByRole('button', { name: 'Place order' }).click()

    // Order status: charged + confirmed
    await page.waitForURL(/\/orders\//)
    await expect(page.getByRole('heading', { name: 'Thank you' })).toBeVisible()
    await expect(page.getByText('Confirmed')).toBeVisible()
    await page.screenshot({ path: `${SCREENSHOTS}/06-order.png`, fullPage: true })
  })

  test('pages expose a sound accessibility structure', async ({ page }) => {
    await page.goto('/')
    await expect(page.getByRole('heading', { name: /Considered goods/ })).toBeVisible()
    const home = await page.locator('body').ariaSnapshot()
    expect(home).toContain('banner')
    expect(home).toContain('link "Lumen')
    console.log(home.split('\n').slice(0, 22).join('\n'))
  })
})