import { test, expect, type Browser, type BrowserContext, type Page, type Response } from '@playwright/test'
import { readFile, mkdir } from 'node:fs/promises'
import { resolve } from 'node:path'

type Account = { username: string; password: string; id: string }
type Row = Record<string, any>
const directory = resolve('../.artifacts')
let accounts: Record<string, Account>
let fixture: Record<string, string>
test.beforeAll(async () => {
  accounts = JSON.parse(await readFile(resolve(directory, 'R-e2e-accounts.json'), 'utf8'))
  fixture = JSON.parse(await readFile(resolve(directory, 'R-e2e-fixtures.json'), 'utf8'))
})

async function result(response: Response, status = 200): Promise<Row> {
  expect(response.status()).toBe(status)
  const envelope = await response.json()
  expect(envelope.code).toBe('OK')
  expect(envelope.traceId).toBeTruthy()
  return envelope.data
}
async function signIn(page: Page, role: string) {
  await page.goto('/login')
  await page.getByLabel('用户名', { exact: true }).fill(accounts[role].username)
  await page.locator('input[autocomplete="current-password"]').fill(accounts[role].password)
  const response = page.waitForResponse(r => r.url().endsWith('/api/v1/auth/login') && r.request().method() === 'POST')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  const auth = await result(await response)
  await expect(page).not.toHaveURL(/\/login/)
  return auth.accessToken as string
}
async function actor(browser: Browser, role: string) {
  const context = await browser.newContext({ baseURL: process.env.E2E_BASE_URL ?? 'http://127.0.0.1:5173', viewport: { width: 1440, height: 1000 } })
  const page = await context.newPage()
  await signIn(page, role)
  return { context, page }
}
async function submitDialog(page: Page, path: string, fields: Record<string, string>, expected: number) {
  const dialog = page.getByRole('dialog').last()
  for (const [label, value] of Object.entries(fields)) {
    const control = dialog.getByLabel(label, { exact: false })
    if (await control.evaluate(el => el.tagName === 'SELECT')) await control.selectOption(value)
    else await control.fill(value)
  }
  const response = page.waitForResponse(r => r.url().includes(path) && r.request().method() === 'POST')
  await dialog.locator('button[type="submit"]').click()
  const data = await result(await response, expected)
  await expect(dialog).not.toBeVisible()
  return data
}
async function noOverflow(page: Page) {
  const dimensions = await page.evaluate(() => [document.documentElement.scrollWidth, window.innerWidth])
  expect(dimensions[0]).toBeLessThanOrEqual(dimensions[1])
}

test('真实页面跨店购买、独立模拟支付、履约评价和退款闭环', async ({ page, browser }, info) => {
  const contexts: BrowserContext[] = []
  const token = await signIn(page, 'buyer')
  // 仅预置购物选择；建单、支付和后续业务操作均通过页面按钮触发真实接口。
  for (const sku of [fixture.sku1, fixture.sku2]) {
    const response = await page.request.put(`/api/v1/cart/items/${sku}`, { headers: { Authorization: `Bearer ${token}` }, data: { quantity: 1, selected: true } })
    expect(response.status()).toBe(200)
  }
  await page.goto('/checkout')
  await page.getByRole('button', { name: '重新获取服务端预览' }).click()
  const created = page.waitForResponse(r => r.url().endsWith('/api/v1/checkout/orders') && r.request().method() === 'POST')
  await page.getByRole('button', { name: '确认并创建独立店铺订单' }).click()
  const batch = await result(await created, 201)
  expect(batch.orders).toHaveLength(2)
  expect(new Set(batch.orders.map((o: Row) => o.shopId)).size).toBe(2)
  const payments: string[] = []
  for (const order of batch.orders as Row[]) {
    await page.goto(`/account/orders/${order.id}`)
    const pending = page.waitForResponse(r => r.url().endsWith(`/orders/${order.id}/payments`) && r.request().method() === 'POST')
    await page.getByRole('button', { name: '创建或继续此订单支付' }).click()
    const payment = await result(await pending, 201)
    payments.push(payment.paymentNo)
    const confirmed = page.waitForResponse(r => r.url().endsWith(`/payments/${payment.id}/mock-confirm`))
    await page.getByRole('button', { name: '本地模拟确认付款' }).click()
    expect((await result(await confirmed)).status).toBe('SUCCEEDED')
  }
  expect(new Set(payments).size).toBe(2)
  const shippedOrder = batch.orders.find((o: Row) => o.shopId === fixture.shop1) as Row
  const refundOrder = batch.orders.find((o: Row) => o.shopId === fixture.shop2) as Row
  try {
    const merchant = await actor(browser, 'merchant'); contexts.push(merchant.context)
    await merchant.page.goto('/workbench/merchant/orders')
    const row = merchant.page.locator('tbody tr').filter({ hasText: shippedOrder.orderNo })
    await row.getByRole('button', { name: '整体发货' }).click()
    await submitDialog(merchant.page, `/orders/${shippedOrder.id}/ship`, { '承运方': '虚构承运商', '物流单号': `E2E-${shippedOrder.id}` }, 201)
    await page.goto(`/account/orders/${shippedOrder.id}`)
    const receipt = page.waitForResponse(r => r.url().endsWith('/confirm-receipt'))
    await page.getByRole('button', { name: '确认收货', exact: true }).click()
    expect((await result(await receipt)).status).toBe('COMPLETED')
    await page.getByRole('link', { name: '评价此书' }).click()
    await page.getByLabel('阅读感受').fill('<script>window.__yuejiXss=true</script>')
    const rejectedReview = page.waitForResponse(r => r.url().endsWith('/api/v1/reviews') && r.request().method() === 'POST')
    await page.getByRole('button', { name: '提交评价', exact: true }).click()
    expect((await rejectedReview).status()).toBe(400)
    expect(await page.evaluate(() => (window as Window & { __yuejiXss?: boolean }).__yuejiXss)).toBeUndefined()
    await page.getByLabel('阅读感受').fill(`真实浏览器评价 ${info.project.name} 纯文本正常提交`)
    const review = page.waitForResponse(r => r.url().endsWith('/api/v1/reviews') && r.request().method() === 'POST')
    await page.getByRole('button', { name: '提交评价', exact: true }).click()
    await result(await review, 201)

    await page.goto(`/account/orders/${refundOrder.id}`)
    await page.getByRole('link', { name: '申请售后' }).click()
    await page.getByLabel('申请原因').fill(`未发货退款 ${info.project.name}`)
    const applied = page.waitForResponse(r => r.url().endsWith('/api/v1/after-sales') && r.request().method() === 'POST')
    await page.getByRole('button', { name: '提交售后申请' }).click()
    const sale = await result(await applied, 201)
    const support = await actor(browser, 'support'); contexts.push(support.context)
    await support.page.goto('/workbench/support/after-sales')
    await support.page.locator('tbody tr').filter({ hasText: sale.afterSaleNo }).getByRole('button', { name: '审核', exact: true }).click()
    const approved = await submitDialog(support.page, `/after-sales/${sale.id}/decision`, { '审核结果': 'true', '审核说明': '客服独立审核浏览器退款' }, 200)
    const finance = await actor(browser, 'finance'); contexts.push(finance.context)
    await finance.page.goto('/workbench/finance/refunds')
    await finance.page.locator('tbody tr').filter({ hasText: approved.refund.refundNo }).getByRole('button', { name: '执行 / 重试' }).click()
    const refund = await submitDialog(finance.page, `/refunds/${approved.refund.id}/execute`, {}, 202)
    expect(refund.status).toBe('SUCCEEDED')
    expect(refund.amount).toBe(refundOrder.payableAmount)
    await page.goto(`/account/orders/${refundOrder.id}`)
    await expect(page.locator('.order-status-panel')).toContainText('退款')
    await noOverflow(page)
    await page.screenshot({ path: resolve(directory, `R-${info.project.name}-refund.png`), fullPage: true })

    // 已收货订单再走退货路径，验证 APPROVED 时运单入口真实可用。
    await page.goto(`/account/orders/${shippedOrder.id}`)
    await page.getByRole('link', { name: '申请售后' }).click()
    await page.getByLabel('申请类型').selectOption('RETURN_REFUND')
    await page.getByLabel('申请原因').fill(`收货后退货 ${info.project.name}`)
    const returnApplied = page.waitForResponse(r => r.url().endsWith('/api/v1/after-sales') && r.request().method() === 'POST')
    await page.getByRole('button', { name: '提交售后申请' }).click()
    const returned = await result(await returnApplied, 201)
    await support.page.goto('/workbench/support/after-sales')
    await support.page.locator('tbody tr').filter({ hasText: returned.afterSaleNo }).getByRole('button', { name: '审核', exact: true }).click()
    await submitDialog(support.page, `/after-sales/${returned.id}/decision`, { '审核结果': 'true', '审核说明': '批准退货等待运单' }, 200)
    await page.goto(`/account/after-sales/${returned.id}`)
    await page.getByLabel('承运方', { exact: true }).fill('虚构退货承运商')
    await page.getByLabel('运单号', { exact: true }).fill(`RETURN-${returned.id}`)
    const sent = page.waitForResponse(r => r.url().endsWith(`/after-sales/${returned.id}/return-shipment`))
    await page.getByRole('button', { name: '提交运单', exact: true }).click()
    expect((await result(await sent)).status).toBe('RETURNING')
    const warehouse = await actor(browser, 'warehouse'); contexts.push(warehouse.context)
    await warehouse.page.goto('/workbench/warehouse/returns')
    await warehouse.page.locator('tbody tr').filter({ hasText: returned.afterSaleNo }).getByRole('button', { name: '验收退货', exact: true }).click()
    await warehouse.page.getByRole('dialog').last().getByLabel('可再售并回补库存').check()
    const inspected = await submitDialog(warehouse.page, `/after-sales/${returned.id}/inspection`, { '退货验收': 'true', '验收说明': '合格可再售' }, 200)
    await finance.page.goto('/workbench/finance/refunds')
    await finance.page.locator('tbody tr').filter({ hasText: inspected.refund.refundNo }).getByRole('button', { name: '执行 / 重试' }).click()
    expect((await submitDialog(finance.page, `/refunds/${inspected.refund.id}/execute`, {}, 202)).status).toBe('SUCCEEDED')
    const other = await actor(browser, 'otherBuyer'); contexts.push(other.context)
    await other.page.goto(`/account/orders/${refundOrder.id}`)
    await expect(other.page.getByRole('alert').or(other.page.locator('.async-error'))).toBeVisible()
  } finally { for (const context of contexts) await context.close() }
})

test('长文本无封面与XSS按纯文本展示，会员后台越权被拒绝', async ({ page }, info) => {
  await page.goto('/search')
  await expect(page.getByText(fixture.xssTitle, { exact: true }).first()).toBeVisible()
  expect(await page.evaluate(() => (window as Window & { __yuejiXss?: boolean }).__yuejiXss)).toBeUndefined()
  await expect(page.locator('.cover-placeholder').first()).toBeVisible()
  await noOverflow(page)
  await page.screenshot({ path: resolve(directory, `R-${info.project.name}-xss.png`), fullPage: true })
  await signIn(page, 'buyer')
  await page.goto('/workbench/finance/settlements')
  await expect(page).toHaveURL(/\/forbidden/)
})

test('[desktop] 真实结算页面创建、职责分离审核及模拟出款', async ({ browser }) => {
  const finance = await actor(browser, 'finance')
  const approver = await actor(browser, 'approver')
  try {
    await finance.page.goto('/workbench/finance/settlements')
    await finance.page.getByRole('button', { name: '生成结算候选', exact: true }).click()
    const yesterday = new Date(Date.now() - 86400000).toLocaleDateString('en-CA', { timeZone: 'Asia/Shanghai' })
    const statement = await submitDialog(finance.page, '/finance/settlements', { '店铺 ID': fixture.shop1, '已结束的北京时间账期': yesterday }, 201)
    const row = finance.page.locator('tbody tr').filter({ hasText: statement.settlementNo })
    await row.getByRole('button', { name: '审核', exact: true }).click()
    const dialog = finance.page.getByRole('dialog').last()
    await dialog.getByLabel('审核结果').selectOption('true')
    await dialog.getByLabel('审核说明').fill('创建人自批应被拒绝')
    const denied = finance.page.waitForResponse(r => r.url().endsWith(`/settlements/${statement.id}/decision`))
    await dialog.locator('button[type="submit"]').click()
    expect((await denied).status()).toBe(403)
    await expect(dialog.getByRole('alert')).toBeVisible()
    await approver.page.goto('/workbench/finance/settlements')
    await approver.page.locator('tbody tr').filter({ hasText: statement.settlementNo }).getByRole('button', { name: '审核', exact: true }).click()
    await submitDialog(approver.page, `/settlements/${statement.id}/decision`, { '审核结果': 'true', '审核说明': '独立财务复核' }, 200)
    await approver.page.locator('tbody tr').filter({ hasText: statement.settlementNo }).getByRole('button', { name: '模拟出款', exact: true }).click()
    const paid = await submitDialog(approver.page, `/settlements/${statement.id}/mock-payout`, {}, 200)
    expect(paid.status).toBe('PAID')
    expect(paid.mockPayoutNo).toMatch(/^MOCK-/)
    await approver.page.screenshot({ path: resolve(directory, 'R-settlement-mock-paid.png'), fullPage: true })
  } finally { await finance.context.close(); await approver.context.close() }
})
