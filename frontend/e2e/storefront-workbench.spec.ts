import { expect, test, type Page } from '@playwright/test'
import { mkdir, readFile } from 'node:fs/promises'
import { join, resolve } from 'node:path'

const baseURL = process.env.E2E_BASE_URL ?? 'http://localhost:5173'
const apiRoot = `${baseURL}/api/v1`
const artifacts = resolve(process.cwd(), '../.artifacts')
const devAccountsPath = resolve(process.cwd(), '../.artifacts/B-dev-accounts.json')
const longBookTitle = '移动端长文本布局验收专用实体新书名称与副标题共计超过常规卡片宽度'
const longAddressName = '阶段G手机端长文本收件人布局测试'
const longAddressDetail = '这是仅用于本地开发库验收的临时地址，不用于配送；此段文本故意加长以验证窄屏换行、按钮可达和页面不会产生横向滚动。'.repeat(2)

type DevAccount = { password: string }
type ApiEnvelope<T> = { code: string; message: string; data: T; traceId: string }
type ProductRow = { id: string; shopId: string; skuId: string; availableQuantity: number }
type CartData = { groups: Array<{ shopId: string; items: Array<{ skuId: string; purchasable: boolean; selected: boolean }> }> }

async function devAccount(username: string) {
  const accounts = JSON.parse(await readFile(devAccountsPath, 'utf8')) as Record<string, DevAccount>
  const account = accounts[username]
  if (!account?.password) throw new Error('缺少项目本机开发账号文件；不会生成或记录新密码。')
  return { username, password: account.password }
}

async function signIn(page: Page, username: string) {
  const credentials = await devAccount(username)
  await page.goto('/login')
  const responsePromise = page.waitForResponse(response =>
    response.url().includes('/api/v1/auth/login') && response.request().method() === 'POST',
  )
  await page.locator('input[autocomplete="username"]').fill(credentials.username)
  await page.locator('input[autocomplete="current-password"]').fill(credentials.password)
  await page.getByRole('button', { name: '登录' }).click()
  const response = await responsePromise
  expect(response.status()).toBe(200)
  const envelope = await response.json() as ApiEnvelope<{ accessToken: string }>
  expect(envelope.code).toBe('OK')
  expect(envelope.data.accessToken).toBeTruthy()
  return { accessToken: envelope.data.accessToken }
}

async function saveScreenshot(page: Page, filename: string) {
  await mkdir(artifacts, { recursive: true })
  await page.screenshot({ path: join(artifacts, filename), fullPage: true })
}

async function assertNoHorizontalOverflow(page: Page) {
  const dimensions = await page.evaluate(() => ({ viewport: window.innerWidth, content: document.documentElement.scrollWidth }))
  expect(dimensions.content, `页面横向溢出：${dimensions.content}px > ${dimensions.viewport}px`).toBeLessThanOrEqual(dimensions.viewport)
}

async function cleanupAccount(page: Page) {
  await page.goto('/account/security')
  const responsePromise = page.waitForResponse(response =>
    response.url().endsWith('/api/v1/auth/logout') && response.request().method() === 'POST',
  )
  await page.getByRole('button', { name: '退出当前会话' }).click()
  const response = await responsePromise
  if (!response.ok()) {
    const envelope = await response.json().catch(() => null) as { code?: string } | null
    throw new Error(`本地开发会话退出失败：HTTP ${response.status()}，code=${envelope?.code ?? 'unknown'}`)
  }
}

test.describe('公开商城手机端布局', () => {
  test('[public]真实商品列表在长书名和无封面状态下换行且可滚动浏览', async ({ page }, testInfo) => {
    test.skip(testInfo.project.name !== 'public-mobile', '此用例只在匿名手机项目中运行。')

    let upstreamItems = 0
    await page.route('**/api/v1/products?*', async route => {
      const upstream = await route.fetch()
      const envelope = await upstream.json() as ApiEnvelope<{ items: Array<{ book: { title: string; author: string; coverUrl: string | null; coverFileId: string | null } }> }>
      upstreamItems = envelope.data.items.length
      if (upstreamItems > 0) {
        envelope.data.items[0].book.title = longBookTitle
        envelope.data.items[0].book.author = '用于页面换行检查的超长作者姓名与协作编者信息'
        envelope.data.items[0].book.coverUrl = null
        envelope.data.items[0].book.coverFileId = null
      }
      await route.fulfill({ response: upstream, body: JSON.stringify(envelope) })
    })

    await page.goto('/search')
    await expect(page.locator('.book-grid')).toBeVisible()
    expect(upstreamItems).toBeGreaterThan(0)
    await expect(page.locator('.book-title').first()).toHaveText(longBookTitle)
    await expect(page.locator('.cover-placeholder[aria-label="暂无封面"]').first()).toBeVisible()
    await assertNoHorizontalOverflow(page)
    await saveScreenshot(page, 'G-mobile-catalog-long-title-no-cover.png')
  })
})

test.describe('会员手机端联调与异常恢复', () => {
  test('立即购买只把当前 SKU 送入真实服务端预览', async ({ page }, testInfo) => {
    test.skip(testInfo.project.name !== 'mobile', '此用例使用手机视口。')

    const auth = await signIn(page, 'reader_a')
    let addressId = ''
    let addedSkuId = ''
    try {
      const [productsResponse, cartResponse] = await Promise.all([
        page.request.get(`${baseURL}/api/v1/products?page=1&pageSize=100`),
        page.request.get(`${apiRoot}/cart`, { headers: { Authorization: `Bearer ${auth.accessToken}` } }),
      ])
      expect(productsResponse.ok()).toBeTruthy()
      expect(cartResponse.ok()).toBeTruthy()
      const productData = (await productsResponse.json() as ApiEnvelope<ProductRow[]> | ApiEnvelope<{ items: ProductRow[] }>).data
      const productItems = Array.isArray(productData) ? productData : productData.items
      const existingCart = (await cartResponse.json() as ApiEnvelope<CartData>).data
      const existingSkus = new Set(existingCart.groups.flatMap(group => group.items.map(item => item.skuId)))
      const candidate = productItems.find(item => item.availableQuantity > 0 && !existingSkus.has(item.skuId))
      expect(candidate, '开发联调数据应提供购物车外的可售 SKU').toBeDefined()
      if (!candidate) return
      addedSkuId = candidate.skuId

      const addressResponse = await page.request.post(`${apiRoot}/addresses`, {
        headers: { Authorization: `Bearer ${auth.accessToken}` },
        data: {
          receiverName: '阶段G立即购买临时地址',
          phone: '13800000000',
          province: '浙江省',
          city: '杭州市',
          district: '西湖区',
          detail: '仅用于本地浏览器验收，不用于配送。',
          isDefault: true,
        },
      })
      expect(addressResponse.status()).toBe(201)
      addressId = (await addressResponse.json() as ApiEnvelope<{id:string}>).data.id

      await page.goto(`/products/${candidate.id}`)
      await expect(page.getByRole('button', { name: '立即购买' })).toBeVisible()
      await assertNoHorizontalOverflow(page)
      await saveScreenshot(page, 'G-mobile-product-buy-now.png')
      const buyResponsePromise = page.waitForResponse(response =>
        response.url().endsWith(`/api/v1/cart/items/${addedSkuId}`) && response.request().method() === 'PUT',
      )
      await page.getByRole('button', { name: '立即购买' }).click()
      const buyResponse = await buyResponsePromise
      expect(buyResponse.ok()).toBeTruthy()
      await expect(page).toHaveURL(/\/checkout\?buyNowSkuId=/)
      await expect(page.locator('.checkout-shop')).toHaveCount(1)

      const previewResponsePromise = page.waitForResponse(response =>
        response.url().endsWith('/api/v1/checkout/previews') && response.request().method() === 'POST',
      )
      await page.getByRole('button', { name: '重新获取服务端预览' }).click()
      const previewResponse = await previewResponsePromise
      expect(previewResponse.ok()).toBeTruthy()
      const preview = (await previewResponse.json() as ApiEnvelope<{shops:Array<{items:Array<{skuId:string}>}>}>).data
      expect(preview.shops).toHaveLength(1)
      expect(preview.shops[0].items.map(item => item.skuId)).toEqual([addedSkuId])
      await assertNoHorizontalOverflow(page)
    } finally {
      if (addedSkuId) {
        const response = await page.request.delete(`${apiRoot}/cart/items/${addedSkuId}`, { headers: { Authorization: `Bearer ${auth.accessToken}` } })
        if (!response.ok()) throw new Error(`清理立即购买临时购物车行失败：HTTP ${response.status()}`)
      }
      if (addressId) {
        const response = await page.request.delete(`${apiRoot}/addresses/${addressId}`, { headers: { Authorization: `Bearer ${auth.accessToken}` } })
        if (!response.ok()) throw new Error(`清理立即购买临时地址失败：HTTP ${response.status()}`)
      }
      await cleanupAccount(page)
    }
  })

  test('地址失败可重试，添加临时地址后生成真实跨店预览，并阻止会员访问财务页', async ({ page }, testInfo) => {
    test.skip(testInfo.project.name !== 'mobile', '此用例使用手机视口。')

    const auth = await signIn(page, 'reader_a')
    let addressId = ''
    let addedSkuId = ''
    let failFirstAddressRead = true
    await page.route('**/api/v1/addresses', async route => {
      if (route.request().method() !== 'GET') return route.continue()
      if (failFirstAddressRead) {
        failFirstAddressRead = false
        return route.fulfill({
          status: 503,
          contentType: 'application/json',
          body: JSON.stringify({ code: 'DEPENDENCY_UNAVAILABLE', message: '测试用的一次性读取故障', data: null, traceId: 'stage-g-address-retry' }),
        })
      }
      return route.continue()
    })

    try {
      await page.goto('/account/addresses')
      await expect(page.getByRole('alert')).toContainText('暂时无法完成请求')
      await expect(page.getByRole('alert')).toContainText('stage-g-address-retry')
      await saveScreenshot(page, 'G-mobile-address-error-retry.png')
      await page.getByRole('button', { name: '重新加载' }).click()
      await expect(page.getByText('还没有保存的收货地址。')).toBeVisible()
      await page.getByRole('button', { name: '添加收货地址' }).click()

      await page.getByLabel('收件人').fill(longAddressName)
      await page.getByLabel('手机号').fill('13800000000')
      await page.getByLabel('省/自治区').fill('浙江省')
      await page.getByLabel('城市').fill('杭州市')
      await page.getByLabel('区/县').fill('西湖区')
      await page.getByLabel('详细地址').fill(longAddressDetail)
      await page.getByLabel('设为默认地址').check()
      const addressResponsePromise = page.waitForResponse(response =>
        response.url().endsWith('/api/v1/addresses') && response.request().method() === 'POST',
      )
      await page.getByRole('button', { name: '保存地址' }).click()
      const addressResponse = await addressResponsePromise
      const addressEnvelope = await addressResponse.json() as ApiEnvelope<{ id: string }>
      expect(addressResponse.status()).toBe(201)
      expect(addressEnvelope.code).toBe('OK')
      addressId = addressEnvelope.data.id
      await expect(page.getByText(longAddressName)).toBeVisible()
      await expect(page.getByText(longAddressDetail)).toBeVisible()
      await assertNoHorizontalOverflow(page)
      await saveScreenshot(page, 'G-mobile-member-long-address.png')

      const [productsResponse, cartResponse] = await Promise.all([
        page.request.get(`${baseURL}/api/v1/products?page=1&pageSize=100`),
        page.request.get(`${apiRoot}/cart`, { headers: { Authorization: `Bearer ${auth.accessToken}` } }),
      ])
      expect(productsResponse.ok()).toBeTruthy()
      expect(cartResponse.ok()).toBeTruthy()
      const products = (await productsResponse.json() as ApiEnvelope<ProductRow[]> | ApiEnvelope<{ items: ProductRow[] }>).data
      const productItems = Array.isArray(products) ? products : products.items
      const cart = (await cartResponse.json() as ApiEnvelope<CartData>).data
      const cartItems = cart.groups.flatMap(group => group.items)
      const activeShops = new Set(cart.groups.flatMap(group => group.items.some(item => item.selected && item.purchasable) ? [group.shopId] : []))
      const existingSkus = new Set(cartItems.map(item => item.skuId))
      const additionalProduct = productItems.find(product =>
        product.availableQuantity > 0 && !existingSkus.has(product.skuId) && !activeShops.has(product.shopId),
      )
      expect(additionalProduct, '开发联调数据应提供另一家店铺的可售 SKU').toBeDefined()
      if (!additionalProduct) return
      addedSkuId = additionalProduct.skuId

      await page.goto(`/products/${additionalProduct.id}`)
      const addResponsePromise = page.waitForResponse(response =>
        response.url().endsWith(`/api/v1/cart/items/${addedSkuId}`) && response.request().method() === 'PUT',
      )
      await page.getByRole('button', { name: '加入购物袋' }).click()
      const addResponse = await addResponsePromise
      expect(addResponse.ok()).toBeTruthy()

      await page.route('**/api/v1/cart', async route => {
        const upstream = await route.fetch()
        const envelope = await upstream.json() as ApiEnvelope<{ groups: Array<{ items: Array<{ title: string; coverUrl: string | null }> }> }>
        const firstItem = envelope.data.groups.flatMap(group => group.items)[0]
        if (firstItem) {
          firstItem.title = longBookTitle
          firstItem.coverUrl = null
        }
        await route.fulfill({ response: upstream, body: JSON.stringify(envelope) })
      })
      await page.goto('/cart')
      await expect(page.locator('.cart-cover .cover-placeholder').first()).toBeVisible()
      await expect(page.locator('.cart-item-main > a').filter({ hasText: longBookTitle }).first()).toBeVisible()
      await assertNoHorizontalOverflow(page)
      await saveScreenshot(page, 'G-mobile-member-cart-long-title-no-cover.png')

      await page.goto('/checkout')
      await expect(page.getByText(longAddressName)).toBeVisible()
      const previewResponsePromise = page.waitForResponse(response =>
        response.url().endsWith('/api/v1/checkout/previews') && response.request().method() === 'POST',
      )
      await page.getByRole('button', { name: '重新获取服务端预览' }).click()
      const previewResponse = await previewResponsePromise
      expect(previewResponse.ok()).toBeTruthy()
      const preview = (await previewResponse.json() as ApiEnvelope<{ shops: unknown[]; totalAmount: string }>).data
      expect(preview.shops).toHaveLength(2)
      expect(preview.totalAmount).toMatch(/^\d+\.\d{2}$/)
      await expect(page.locator('.preview-shop')).toHaveCount(2)
      await expect(page.locator('.total-grand')).toBeVisible()
      await assertNoHorizontalOverflow(page)
      await saveScreenshot(page, 'G-mobile-cross-store-server-preview.png')

      await page.goto('/workbench/finance/settlements')
      await expect(page).toHaveURL(/\/forbidden$/)
      await expect(page.getByRole('heading', { name: /当前账号没有此操作权限/ })).toBeVisible()
    } finally {
      if (addedSkuId) {
        const response = await page.request.delete(`${apiRoot}/cart/items/${addedSkuId}`, { headers: { Authorization: `Bearer ${auth.accessToken}` } })
        if (!response.ok()) throw new Error(`清理临时购物车行失败：HTTP ${response.status()}`)
      }
      if (addressId) {
        const response = await page.request.delete(`${apiRoot}/addresses/${addressId}`, { headers: { Authorization: `Bearer ${auth.accessToken}` } })
        if (!response.ok()) throw new Error(`清理临时地址失败：HTTP ${response.status()}`)
      }
      await cleanupAccount(page)
    }
  })
})

test.describe('桌面管理工作台', () => {
  test('管理员表格容纳长昵称，API-89 明确显示真实未实现错误', async ({ page }, testInfo) => {
    test.skip(testInfo.project.name !== 'desktop', '此用例使用桌面工作台视口。')
    const auth = await signIn(page, 'admin_a')
    let longNicknameRendered = false

    await page.route('**/api/v1/admin/users*', async route => {
      const upstream = await route.fetch()
      const envelope = await upstream.json() as ApiEnvelope<{ items: Array<{ nickname: string }> }>
      if (envelope.data.items.length > 0) {
        envelope.data.items[0].nickname = '桌面后台长文本列宽与换行验收用管理员显示名称'
        longNicknameRendered = true
      }
      await route.fulfill({ response: upstream, body: JSON.stringify(envelope) })
    })

    try {
      await page.goto('/workbench/admin/users')
      await expect(page.locator('.responsive-table tbody tr').first()).toBeVisible()
      expect(longNicknameRendered).toBe(true)
      await expect(page.getByText('桌面后台长文本列宽与换行验收用管理员显示名称')).toBeVisible()
      await assertNoHorizontalOverflow(page)
      await saveScreenshot(page, 'G-desktop-workbench-long-text.png')

      await page.goto('/workbench')
      await expect(page.getByRole('alert')).toContainText('暂时无法完成请求')
      await expect(page.getByRole('alert')).toContainText('跟踪号')
      await expect(page.locator('.metric-card')).toHaveCount(0)
      await saveScreenshot(page, 'G-desktop-api89-not-implemented.png')
    } finally {
      await cleanupAccount(page)
    }
  })
})
