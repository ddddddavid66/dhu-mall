// 只运行于明确批准的test；按01口径分开记录查询与内部下单延迟，排除外部支付。
import http from 'k6/http'
import { check, fail } from 'k6'
import { Trend, Rate, Counter } from 'k6/metrics'
import execution from 'k6/execution'

if (__ENV.TARGET_ENV !== 'test' || __ENV.CONFIRM_ISOLATED !== 'true') throw new Error('需要明确test环境与隔离确认')
const target = __ENV.BASE_URL
if (!target) throw new Error('BASE_URL required')
const accounts = JSON.parse(open(__ENV.PERF_ACCOUNTS_FILE))
if (accounts.length < 300 || !/^[A-Za-z0-9_-]{20,40}$/.test(__ENV.RUN_ID ?? '')) throw new Error('需要至少300个独立会员及20—40字符运行编号')
const queryDuration = new Trend('product_query_duration', true)
const orderDuration = new Trend('internal_order_duration', true)
const unexpected = new Rate('unexpected_error_rate')
const conflicts = new Counter('expected_business_conflicts')
export const options = {
  scenarios: {
    warmupRead: { executor: 'constant-arrival-rate', exec: 'browse', rate: 80, timeUnit: '1s', duration: '5m', preAllocatedVUs: 50, maxVUs: 100, tags: { phase: 'warmup' } },
    read: { executor: 'constant-arrival-rate', exec: 'browse', startTime: '5m', rate: 80, timeUnit: '1s', duration: '10m', preAllocatedVUs: 50, maxVUs: 100, tags: { phase: 'measure' } },
    order: { executor: 'constant-arrival-rate', exec: 'order', startTime: '5m', rate: 20, timeUnit: '1s', duration: '10m', preAllocatedVUs: 50, maxVUs: 200, tags: { phase: 'measure' } },
  },
  thresholds: { 'product_query_duration{phase:measure}': ['p(95)<=500'], 'internal_order_duration{phase:measure}': ['p(95)<=800'],
    'unexpected_error_rate{phase:measure}': ['rate<0.01'], dropped_iterations: ['count==0'], checks: ['rate==1'] },
}
function observe(response, expected, metric, phase) {
  const bad = response.status === 0 || response.status >= 500
  unexpected.add(bad, { phase })
  if (response.status === 409) conflicts.add(1)
  check(response, { 'contract response': r => r.status === expected || r.status === 409 })
  if (metric) metric.add(response.timings.duration, { phase })
  if (bad) return null
  try { return response.json('data') } catch { return null }
}
export function browse() {
  const phase = execution.scenario.name === 'warmupRead' ? 'warmup' : 'measure'
  observe(http.get(`${target}/api/v1/products?page=1&pageSize=20`, { tags: { operation: 'API-12' } }), 200, queryDuration, phase)
}
export function order() {
  const account = accounts[(__VU - 1) % accounts.length]
  if (!account) fail('不足的独立压测会员')
  const headers = { Authorization: `Bearer ${account.accessToken}`, 'Content-Type': 'application/json' }
  const cart = observe(http.put(`${target}/api/v1/cart/items/${account.skuId}`, JSON.stringify({ quantity: 1, selected: true }), { headers }), 200, null, 'measure')
  if (!cart) return
  const rows = cart.groups.flatMap(group => group.items).filter(item => item.selected && item.purchasable)
  const preview = observe(http.post(`${target}/api/v1/checkout/previews`, JSON.stringify({ addressId: account.addressId, cartItemIds: rows.map(row => row.id) }), { headers }), 201, null, 'measure')
  if (!preview) return
  const key = `${__ENV.RUN_ID}-${__VU}-${__ITER}`
  observe(http.post(`${target}/api/v1/checkout/orders`, JSON.stringify({ previewId: preview.previewId }), { headers: { ...headers, 'Idempotency-Key': key } }), 201, orderDuration, 'measure')
}
export function handleSummary(data) {
  return { 'stdout': JSON.stringify(data, null, 2), [__ENV.RESULT_FILE ?? '.artifacts/R-k6-summary.json']: JSON.stringify(data, null, 2) }
}
