<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AsyncState from '../../components/AsyncState.vue'
import MoneyText from '../../components/MoneyText.vue'
import { ApiError, callOperation } from '../../api/client'
import type { AddressView, AfterSaleView, BatchView, CartView, OrderSummary, OrderView, Page, PaymentView, UserView } from '../../api/models'
import { useSessionStore } from '../../stores/session'

const route = useRoute()
const router = useRouter()
const session = useSessionStore()
const pageType = computed(() => String(route.meta.page))
const loading = ref(false)
const error = ref<unknown>(null)
const actionError = ref<unknown>(null)
const cart = ref<CartView | null>(null)
const addresses = ref<AddressView[]>([])
const preview = ref<Awaited<ReturnType<typeof createPreview>> | null>(null)
const batch = ref<BatchView | null>(null)
const orders = ref<Page<OrderSummary> | null>(null)
const order = ref<OrderView | null>(null)
const afterSales = ref<Page<AfterSaleView> | null>(null)
const afterSale = ref<AfterSaleView | null>(null)
const currentUser = ref<UserView | null>(null)
const selectedAddressId = ref('')
const addressDraft = ref<AddressView | null>(null)
const editingAddressId = ref<string | null>(null)
const authForm = ref({ username: '', password: '', nickname: '' })
const showPassword = ref(false)
const formMessage = ref('')
const busy = ref(false)
const cancelReason = ref('我申请取消此订单')
const saleType = ref<'REFUND_ONLY' | 'RETURN_REFUND'>('REFUND_ONLY')
const saleQuantity = ref(1)
const saleReason = ref('')
const evidenceFiles = ref<File[]>([])
const uploadedEvidence = ref<Array<{ id: string; name: string }>>([])
const returnDraft = ref({ carrier: '', trackingNo: '' })
const reviewDraft = ref({ rating: 5, content: '' })
const payment = ref<PaymentView | null>(null)
const paymentRefresh = ref(false)
const paymentPollingFinished = ref(false)
const passwordDraft = ref({ oldPassword: '', newPassword: '', confirmPassword: '' })
const addressMessage = ref('')
let activeRequest: AbortController | null = null
let pollTimer: ReturnType<typeof setTimeout> | undefined
let paymentStartedAt = 0

async function createPreview() {
  return callOperation<{
    previewId: string; expiresAt: string; address: AddressView; shops: Array<{ shopId: string; shopName: string; items: Array<{ skuId: string; title: string; quantity: number; unitPrice: string; lineAmount: string }>; goodsAmount: string; freightAmount: string; payableAmount: string }>; totalAmount: string
  }>('API-26', { body: { addressId: selectedAddressId.value, cartItemIds: selectedCartItemIds.value } })
}

const buyNowSkuId = computed(() => pageType.value === 'checkout' && typeof route.query.buyNowSkuId === 'string' ? route.query.buyNowSkuId : '')
const checkoutGroups = computed(() => (cart.value?.groups ?? []).map(group => ({
  ...group,
  items: group.items.filter(item => item.selected && item.purchasable && (!buyNowSkuId.value || item.skuId === buyNowSkuId.value)),
})).filter(group => group.items.length > 0))
const selectedCartItemIds = computed(() => (buyNowSkuId.value
  ? checkoutGroups.value.flatMap(group => group.items.map(item => item.id))
  : (cart.value?.groups ?? []).flatMap(group => group.items.filter(item => item.selected && item.purchasable).map(item => item.id))))
const activeOrderItemId = computed(() => String(route.query.orderItemId ?? ''))
const activeOrderId = computed(() => String(route.query.orderId ?? ''))
const selectedOrderItem = computed(() => order.value?.items.find(item => item.id === activeOrderItemId.value) ?? null)
const filteredOrders = computed(() => orders.value?.items ?? [])
const pageTitle = computed(() => ({
  login: '欢迎回到阅集', register: '创建阅集账户', cart: '我的购物袋', checkout: '确认订单', batch: '订单已建立', payment: '订单支付',
  orders: '我的订单', order: '订单详情', addresses: '收货地址', 'after-sale-new': '申请售后', afterSales: '我的售后', 'after-sales': '我的售后',
  afterSale: '售后进度', 'after-sale': '售后进度', 'review-new': '分享阅读感受', security: '账户安全',
} as Record<string, string>)[pageType.value] ?? '会员中心')

function userScope() { return session.user?.id ?? 'signed-in' }
function timeText(value?: string | null) { return value ? new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false }) : '—' }
function statusLabel(value: string) {
  const labels: Record<string, string> = {
    PENDING_PAYMENT: '待付款', CLOSING: '关闭处理中', CANCELLED: '已取消', PAID: '已付款，待发货', SHIPPED: '已发货', COMPLETED: '已完成', CLOSED_REFUND: '已退款关闭',
    UNPAID: '未付款', PARTIALLY_REFUNDED: '部分退款', REFUNDED: '已退款', REQUESTED: '等待审核', APPROVED: '审核通过', RETURNING: '等待寄回', RECEIVED: '仓库已收货',
    REFUNDING: '退款处理中', REJECTED: '已拒绝', EXPIRED: '已过期', CREATED: '待执行', PROCESSING: '处理中', SUCCEEDED: '成功', FAILED: '失败',
  }
  return labels[value] ?? value
}

async function load() {
  activeRequest?.abort()
  const controller = new AbortController()
  activeRequest = controller
  loading.value = true
  error.value = null
  actionError.value = null
  try {
    if (pageType.value === 'cart') cart.value = await callOperation<CartView>('API-23', { signal: controller.signal })
    else if (pageType.value === 'checkout') {
      const [cartView, addressList] = await Promise.all([
        callOperation<CartView>('API-23', { signal: controller.signal }),
        callOperation<AddressView[]>('API-07', { signal: controller.signal }),
      ])
      cart.value = cartView
      addresses.value = addressList
      selectedAddressId.value = addressList.find(address => address.isDefault)?.id ?? addressList[0]?.id ?? ''
    } else if (pageType.value === 'batch') batch.value = await callOperation<BatchView>('API-28', { path: { id: String(route.params.id) }, signal: controller.signal })
    else if (pageType.value === 'payment') await loadPayment()
    else if (pageType.value === 'orders') {
      orders.value = await callOperation<Page<OrderSummary>>('API-29', { query: { status: typeof route.query.status === 'string' ? route.query.status : undefined, orderNo: typeof route.query.orderNo === 'string' ? route.query.orderNo : undefined, page: typeof route.query.page === 'string' ? Number(route.query.page) : 1, pageSize: 20 }, signal: controller.signal })
    } else if (pageType.value === 'order') order.value = await callOperation<OrderView>('API-30', { path: { id: String(route.params.id) }, signal: controller.signal })
    else if (pageType.value === 'addresses') addresses.value = await callOperation<AddressView[]>('API-07', { signal: controller.signal })
    else if (pageType.value === 'after-sales') afterSales.value = await callOperation<Page<AfterSaleView>>('API-43', { query: { page: typeof route.query.page === 'string' ? Number(route.query.page) : 1, pageSize: 20, status: typeof route.query.status === 'string' ? route.query.status : undefined }, signal: controller.signal })
    else if (pageType.value === 'after-sale') afterSale.value = await callOperation<AfterSaleView>('API-44', { path: { id: String(route.params.id) }, signal: controller.signal })
    else if (pageType.value === 'after-sale-new' || pageType.value === 'review-new') {
      if (!activeOrderId.value || !activeOrderItemId.value) throw new Error('请从订单详情选择订单行后继续。')
      order.value = await callOperation<OrderView>('API-30', { path: { id: activeOrderId.value }, signal: controller.signal })
      if (!selectedOrderItem.value) throw new Error('所选订单行不属于此订单，请返回订单详情重新选择。')
      if (pageType.value === 'after-sale-new') {
        if (!['PAID','COMPLETED'].includes(order.value.status)) throw new Error('当前订单状态不符合售后申请条件，请查看订单详情中的可用操作。')
        saleType.value = order.value.status === 'COMPLETED' ? 'RETURN_REFUND' : 'REFUND_ONLY'
      } else {
        if (order.value.status !== 'COMPLETED') throw new Error('确认收货后才能评价订单商品。')
        if (selectedOrderItem.value.refundedQuantity >= selectedOrderItem.value.quantity) throw new Error('此订单行已全额退款，不能新增评价。')
        if (order.value.completedAt && Date.now() - Date.parse(order.value.completedAt) > 30 * 86400000) throw new Error('评价窗口已结束。')
      }
    } else if (pageType.value === 'security') currentUser.value = await callOperation<UserView>('API-05', { signal: controller.signal })
  } catch (cause) {
    if (!(cause instanceof DOMException && cause.name === 'AbortError')) error.value = cause
  } finally {
    if (!controller.signal.aborted) loading.value = false
  }
}

function fieldErrorMessage(cause: unknown) {
  return cause instanceof ApiError ? `${cause.message}（跟踪号 ${cause.result.traceId}）` : cause instanceof Error ? cause.message : '操作未完成，请重试。'
}

async function login() {
  busy.value = true
  formMessage.value = ''
  try {
    await session.login(authForm.value.username.trim(), authForm.value.password)
    const next = route.query.returnTo
    const destination = typeof next === 'string' && next.startsWith('/') && !next.startsWith('//') ? next : '/account/orders'
    await router.replace(destination)
  } catch (cause) { formMessage.value = fieldErrorMessage(cause) }
  finally { busy.value = false }
}

async function register() {
  busy.value = true
  formMessage.value = ''
  if (new TextEncoder().encode(authForm.value.password).length < 12 || new TextEncoder().encode(authForm.value.password).length > 72) {
    formMessage.value = '密码需为 12—72 个 UTF-8 字节。'
    busy.value = false
    return
  }
  try {
    await session.register({ username: authForm.value.username.trim(), password: authForm.value.password, nickname: authForm.value.nickname.trim() })
    await router.replace({ name: 'login', query: route.query.returnTo ? { returnTo: route.query.returnTo } : {} })
    formMessage.value = '注册完成，请使用新账户登录。'
  } catch (cause) { formMessage.value = fieldErrorMessage(cause) }
  finally { busy.value = false }
}

async function updateCart(skuId: string, quantity: number, selected: boolean) {
  actionError.value = null
  try { cart.value = await callOperation<CartView>('API-24', { path: { skuId }, body: { quantity, selected } }) }
  catch (cause) { actionError.value = cause }
}
async function removeCartItem(skuId: string) {
  actionError.value = null
  try { await callOperation('API-25', { path: { skuId } }); await load() }
  catch (cause) { actionError.value = cause }
}
async function openPreview() {
  actionError.value = null
  try { preview.value = await createPreview() }
  catch (cause) { actionError.value = cause }
}
async function createOrders() {
  if (!preview.value) return
  busy.value = true
  actionError.value = null
  try {
    const created = await callOperation<BatchView>('API-27', {
      body: { previewId: preview.value.previewId }, idempotencyScope: `${userScope()}:preview:${preview.value.previewId}`,
    })
    preview.value = null
    await router.push(`/checkout/batches/${created.batchId}`)
  } catch (cause) { actionError.value = cause }
  finally { busy.value = false }
}
async function payOrder(orderId: string) {
  busy.value = true
  actionError.value = null
  try {
    const created = await callOperation<PaymentView>('API-32', {
      path: { id: orderId }, idempotencyScope: `${userScope()}:order:${orderId}`,
    })
    await router.push(`/payments/${created.id}`)
    payment.value = created
  } catch (cause) { actionError.value = cause }
  finally { busy.value = false }
}
async function cancelOrder() {
  if (!order.value) return
  busy.value = true
  actionError.value = null
  try {
    order.value = await callOperation<OrderView>('API-31', {
      path: { id: order.value.id }, body: { reason: cancelReason.value.trim() }, idempotencyScope: `${userScope()}:order:${order.value.id}`,
    })
  } catch (cause) { actionError.value = cause }
  finally { busy.value = false }
}
async function confirmReceipt() {
  if (!order.value) return
  busy.value = true
  actionError.value = null
  try {
    order.value = await callOperation<OrderView>('API-36', { path: { id: order.value.id }, idempotencyScope: `${userScope()}:order:${order.value.id}` })
  } catch (cause) { actionError.value = cause }
  finally { busy.value = false }
}

function chooseEvidence(event: Event) {
  const files = Array.from((event.target as HTMLInputElement).files ?? [])
  evidenceFiles.value = files.slice(0, 5)
  uploadedEvidence.value = []
}
async function submitAfterSale() {
  if (!selectedOrderItem.value || !order.value) return
  busy.value = true
  actionError.value = null
  try {
    const evidenceFileIds: string[] = []
    for (const file of evidenceFiles.value) {
      const form = new FormData()
      form.append('file', file)
      form.append('purpose', 'AFTER_SALE_EVIDENCE')
      const uploaded = await callOperation<{ id: string }>('API-21', { body: form })
      evidenceFileIds.push(uploaded.id)
    }
    const result = await callOperation<AfterSaleView>('API-42', {
      body: { orderItemId: selectedOrderItem.value.id, type: saleType.value, quantity: saleQuantity.value, reason: saleReason.value.trim(), evidenceFileIds },
      idempotencyScope: `${userScope()}:order-item:${selectedOrderItem.value.id}`,
    })
    await router.push(`/account/after-sales/${result.id}`)
  } catch (cause) { actionError.value = cause }
  finally { busy.value = false }
}
async function cancelAfterSale() {
  if (!afterSale.value) return
  busy.value = true
  actionError.value = null
  try { afterSale.value = await callOperation<AfterSaleView>('API-45', { path: { id: afterSale.value.id } }) }
  catch (cause) { actionError.value = cause }
  finally { busy.value = false }
}
async function sendReturnShipment() {
  if (!afterSale.value) return
  busy.value = true
  actionError.value = null
  try { afterSale.value = await callOperation<AfterSaleView>('API-46', { path: { id: afterSale.value.id }, body: returnDraft.value }) }
  catch (cause) { actionError.value = cause }
  finally { busy.value = false }
}
async function submitReview() {
  if (!selectedOrderItem.value) return
  busy.value = true
  actionError.value = null
  try {
    await callOperation('API-37', { body: { orderItemId: selectedOrderItem.value.id, rating: reviewDraft.value.rating, content: reviewDraft.value.content.trim() } })
    formMessage.value = '评价已提交，不能修改或删除。'
    await router.push(`/account/orders/${activeOrderId.value}`)
  } catch (cause) { actionError.value = cause }
  finally { busy.value = false }
}

function editAddress(value?: AddressView) {
  addressDraft.value = value ? { ...value } : { id: '' as AddressView['id'], receiverName: '', phone: '', province: '', city: '', district: '', detail: '', isDefault: false }
  editingAddressId.value = value?.id ?? null
  addressMessage.value = ''
}
async function saveAddress() {
  if (!addressDraft.value) return
  busy.value = true
  addressMessage.value = ''
  const { receiverName, phone, province, city, district, detail, isDefault } = addressDraft.value
  const body = { receiverName: receiverName.trim(), phone: phone.trim(), province: province.trim(), city: city.trim(), district: district.trim(), detail: detail.trim(), isDefault }
  try {
    if (editingAddressId.value) await callOperation('API-09', { path: { id: editingAddressId.value }, body })
    else await callOperation('API-08', { body })
    addressDraft.value = null
    await load()
  } catch (cause) { addressMessage.value = fieldErrorMessage(cause) }
  finally { busy.value = false }
}
async function deleteAddress(addressId: string) {
  if (!window.confirm('删除这个地址？既有订单的收货快照不会改变。')) return
  try { await callOperation('API-10', { path: { id: addressId } }); await load() }
  catch (cause) { actionError.value = cause }
}
async function setDefault(address: AddressView) {
  try {
    const { receiverName, phone, province, city, district, detail } = address
    await callOperation('API-09', { path: { id: address.id }, body: { receiverName, phone, province, city, district, detail, isDefault: true } })
    await load()
  } catch (cause) { actionError.value = cause }
}

async function changePassword() {
  actionError.value = null
  if (passwordDraft.value.newPassword !== passwordDraft.value.confirmPassword) { actionError.value = new Error('两次输入的新密码不一致。'); return }
  if (new TextEncoder().encode(passwordDraft.value.newPassword).length < 12 || new TextEncoder().encode(passwordDraft.value.newPassword).length > 72) { actionError.value = new Error('新密码需为 12—72 个 UTF-8 字节。'); return }
  busy.value = true
  try {
    await session.changePassword(passwordDraft.value.oldPassword, passwordDraft.value.newPassword)
    await router.replace({ name: 'login' })
  } catch (cause) { actionError.value = cause }
  finally { busy.value = false }
}

async function loadPayment() {
  const result = await callOperation<PaymentView>('API-33', { path: { id: String(route.params.id) } })
  const action = payment.value?.id === result.id ? payment.value.paymentAction : null
  if (!result.paymentAction && result.status === 'PENDING' && action && (!action.expiresAt || Date.parse(action.expiresAt) > Date.now())) result.paymentAction = action
  payment.value = result
  if (['SUCCEEDED', 'CLOSED'].includes(result.status)) paymentPollingFinished.value = true
}
function schedulePaymentPoll() {
  if (pollTimer) clearTimeout(pollTimer)
  if (pageType.value !== 'payment' || paymentPollingFinished.value || document.hidden) return
  const elapsed = Date.now() - paymentStartedAt
  if (elapsed >= 5 * 60_000) { paymentPollingFinished.value = true; return }
  const delay = elapsed < 60_000 ? 3000 : 10000
  pollTimer = setTimeout(async () => {
    paymentRefresh.value = true
    try { await loadPayment() } catch (cause) { actionError.value = cause }
    finally { paymentRefresh.value = false; schedulePaymentPoll() }
  }, delay)
}
async function refreshPaymentManually() {
  paymentPollingFinished.value = false
  try { await loadPayment(); schedulePaymentPoll() }
  catch (cause) { actionError.value = cause }
}
function paymentVisibilityChanged() {
  if (document.hidden) { if (pollTimer) clearTimeout(pollTimer); return }
  if (pageType.value === 'payment' && !paymentPollingFinished.value) void refreshPaymentManually()
}
async function confirmMockPayment() {
  if (!payment.value || payment.value.provider !== 'LOCAL_MOCK' || payment.value.status !== 'PENDING') return
  busy.value = true
  actionError.value = null
  try {
    payment.value = await callOperation<PaymentView>('API-34', { path: { id: payment.value.id }, idempotencyScope: `${userScope()}:payment:${payment.value.id}` })
    if (payment.value.status === 'SUCCEEDED') paymentPollingFinished.value = true
  } catch (cause) { actionError.value = cause }
  finally { busy.value = false }
}
async function setOrderFilter(status: string) {
  await router.push({ path: '/account/orders', query: status ? { status } : {} })
}

onMounted(() => {
  document.addEventListener('visibilitychange', paymentVisibilityChanged)
  if (pageType.value === 'payment') paymentStartedAt = Date.now()
  void load()
})
watch(() => route.fullPath, () => {
  if (pollTimer) clearTimeout(pollTimer)
  if (pageType.value === 'payment') { paymentPollingFinished.value = false; paymentStartedAt = Date.now() }
  void load()
})
watch([loading, payment], () => { if (!loading.value && payment.value && pageType.value === 'payment') schedulePaymentPoll() })
onBeforeUnmount(() => {
  activeRequest?.abort()
  if (pollTimer) clearTimeout(pollTimer)
  document.removeEventListener('visibilitychange', paymentVisibilityChanged)
})
</script>

<template>
  <section class="member-page">
    <div v-if="pageType === 'login' || pageType === 'register'" class="auth-page">
      <div class="auth-art"><span class="book-ornament" aria-hidden="true">阅</span><p>慢一点，读一本好书。</p></div>
      <form class="auth-card" @submit.prevent="pageType === 'login' ? login() : register()">
        <p class="eyebrow">{{ pageType === 'login' ? '欢迎回来' : '加入阅集' }}</p><h1>{{ pageTitle }}</h1>
        <label>用户名<input v-model="authForm.username" autocomplete="username" minlength="4" maxlength="32" required /></label>
        <label v-if="pageType === 'register'">昵称<input v-model="authForm.nickname" autocomplete="nickname" maxlength="40" required /></label>
        <label>密码<span class="password-input"><input v-model="authForm.password" :type="showPassword ? 'text' : 'password'" :autocomplete="pageType === 'login' ? 'current-password' : 'new-password'" maxlength="72" required /><button class="text-button" type="button" @click="showPassword = !showPassword">{{ showPassword ? '隐藏' : '显示' }}</button></span></label>
        <p v-if="pageType === 'register'" class="field-hint">密码需为 12—72 个 UTF-8 字节；注册后请使用新账户登录。</p>
        <p v-if="formMessage" class="inline-message" role="status">{{ formMessage }}</p>
        <button class="button button-wide" type="submit" :disabled="busy || session.busy">{{ busy || session.busy ? '请稍候…' : pageType === 'login' ? '登录' : '注册' }}</button>
        <p class="auth-switch">{{ pageType === 'login' ? '还没有账户？' : '已经有阅集账户？' }}
          <RouterLink :to="{ name: pageType === 'login' ? 'register' : 'login', query: route.query }">{{ pageType === 'login' ? '创建账户' : '直接登录' }}</RouterLink>
        </p>
      </form>
    </div>

    <template v-else-if="pageType === 'cart'">
      <div class="page-heading"><p class="eyebrow">会员中心</p><h1>{{ pageTitle }}</h1></div>
      <AsyncState :loading="loading" :error="error" :empty="!(cart?.groups.some(group => group.items.length))" empty-text="购物袋还没有图书，先去目录看看。" @retry="load"><template #empty-action><RouterLink class="button" to="/search">浏览图书</RouterLink></template>
        <p v-if="actionError" class="inline-error" role="alert">{{ fieldErrorMessage(actionError) }}</p>
        <div class="cart-groups">
          <section v-for="group in cart?.groups" :key="group.shopId" class="cart-shop-group">
            <h2>{{ group.shopName }} <span class="muted">· 单店独立订单</span></h2>
            <article v-for="item in group.items" :key="item.id" class="cart-item">
              <input :id="`select-${item.id}`" type="checkbox" :checked="item.selected" :disabled="!item.purchasable" @change="updateCart(item.skuId, item.quantity, ($event.target as HTMLInputElement).checked)" />
              <label class="cart-cover" :for="`select-${item.id}`"><img v-if="item.coverUrl" :src="item.coverUrl" :alt="`《${item.title}》封面`" /><span v-else class="cover-placeholder"><strong>{{ item.title }}</strong><small>暂无封面</small></span></label>
              <div class="cart-item-main"><RouterLink :to="`/products/${item.productId}`">{{ item.title }}</RouterLink><span v-if="!item.purchasable" class="stock-tag stock-out">{{ item.unavailableReason || '当前不可购买' }}</span><MoneyText :value="item.salePrice" /></div>
              <label class="quantity-control">数量<input type="number" min="1" max="99" :value="item.quantity" @change="updateCart(item.skuId, Math.max(1, Math.min(99, Number(($event.target as HTMLInputElement).value))), item.selected)" /></label>
              <button class="text-button danger-link" type="button" @click="removeCartItem(item.skuId)">移除</button>
            </article>
          </section>
        </div>
        <div class="cart-summary"><span>已选择 {{ selectedCartItemIds.length }} 项</span><RouterLink class="button" :class="{ 'button-disabled': !selectedCartItemIds.length }" :to="selectedCartItemIds.length ? '/checkout' : '/cart'">去结算</RouterLink></div>
      </AsyncState>
    </template>

    <template v-else-if="pageType === 'checkout'">
      <div class="page-heading"><p class="eyebrow">最后确认</p><h1>{{ pageTitle }}</h1><p>金额、价格与库存以本次服务端预览为准；跨店订单各自支付。</p></div>
      <AsyncState :loading="loading" :error="error" :empty="false" @retry="load">
        <p v-if="actionError" class="inline-error" role="alert">{{ fieldErrorMessage(actionError) }}</p>
        <section class="checkout-panel"><h2>收货地址</h2>
          <template v-if="addresses.length">
            <label v-for="address in addresses" :key="address.id" class="address-choice" :class="{ selected: selectedAddressId === address.id }">
              <input v-model="selectedAddressId" type="radio" :value="address.id" />
              <span><strong>{{ address.receiverName }}　{{ address.phone }}</strong><small>{{ address.province }}{{ address.city }}{{ address.district }}{{ address.detail }}</small></span><span v-if="address.isDefault" class="stock-tag">默认</span>
            </label>
          </template>
          <AsyncState v-else :loading="false" :empty="true" empty-text="还没有收货地址。"><template #empty-action><RouterLink class="button button-secondary" to="/account/addresses">添加地址</RouterLink></template></AsyncState>
        </section>
        <section class="checkout-panel"><h2>本次选购</h2>
          <p v-if="!selectedCartItemIds.length" class="helper-note">没有可结算的已选商品，请返回购物袋选择有货商品。</p>
          <p v-for="group in checkoutGroups" :key="group.shopId" class="checkout-shop">{{ group.shopName }} · {{ group.items.length }} 项</p>
          <p>商品与库存会在提交时再次校验；包邮。</p>
        </section>
        <section v-if="preview" class="preview-panel" aria-live="polite"><div class="section-title"><div><p class="eyebrow">服务器报价</p><h2>确认预览</h2></div><time>有效至 {{ timeText(preview.expiresAt) }}</time></div>
          <article v-for="shop in preview.shops" :key="shop.shopId" class="preview-shop"><h3>{{ shop.shopName }}</h3><p v-for="item in shop.items" :key="item.skuId">{{ item.title }} × {{ item.quantity }} <MoneyText :value="item.lineAmount" /></p><div class="total-line"><span>本店应付（包邮）</span><MoneyText :value="shop.payableAmount" /></div></article>
          <div class="total-line total-grand"><span>全部店铺合计</span><MoneyText :value="preview.totalAmount" /></div>
          <button class="button" :disabled="busy" @click="createOrders">{{ busy ? '正在提交…' : '确认并创建独立店铺订单' }}</button>
        </section>
        <div class="checkout-actions"><button class="button button-secondary" :disabled="!addresses.length || !selectedCartItemIds.length || busy" @click="openPreview">重新获取服务端预览</button><RouterLink class="text-button" to="/cart">返回购物袋</RouterLink></div>
      </AsyncState>
    </template>

    <template v-else-if="pageType === 'batch'">
      <div class="page-heading"><p class="eyebrow">跨店结算批次 {{ batch?.batchNo }}</p><h1>每家店铺的订单分别处理</h1><p>下面的订单互不合并付款，支付或取消一个订单不会改变其他订单。</p></div>
      <AsyncState :loading="loading" :error="error" :empty="!batch?.orders.length" empty-text="暂时没有订单记录。" @retry="load">
        <div class="order-cards"><article v-for="item in batch?.orders" :key="item.id" class="order-card"><div class="order-card-top"><strong>{{ item.shopName }}</strong><span class="status-pill">{{ statusLabel(item.status) }}</span></div><p>订单号 {{ item.orderNo }}</p><div class="total-line"><span>本店应付</span><MoneyText :value="item.payableAmount" /></div><div class="button-row"><RouterLink class="button button-secondary" :to="`/account/orders/${item.id}`">订单详情</RouterLink><button v-if="item.status === 'PENDING_PAYMENT'" class="button" @click="payOrder(item.id)">单独去支付</button></div></article></div>
      </AsyncState>
    </template>

    <template v-else-if="pageType === 'payment'">
      <div class="page-heading"><p class="eyebrow">单订单支付</p><h1>{{ pageTitle }}</h1><p>页面回跳参数不会改变付款结果，只读取 API-33 的服务端状态。</p></div>
      <AsyncState :loading="loading" :error="error" :empty="!payment" empty-text="没有找到这笔支付记录。" @retry="load">
        <section v-if="payment" class="payment-card"><p class="status-pill" :class="`status-${payment.status.toLowerCase()}`">{{ statusLabel(payment.status) }}</p><p class="payment-number">支付单号 {{ payment.paymentNo }}</p><p class="payment-amount"><MoneyText :value="payment.amount" /></p><p class="helper-note">{{ payment.provider === 'ALIPAY_SANDBOX' ? '支付宝沙箱支付' : payment.provider === 'LOCAL_MOCK' ? '本地模拟支付（仅开发/测试环境）' : payment.provider }}</p>
          <a v-if="payment.paymentAction?.type === 'REDIRECT' && payment.paymentAction.url" class="button" :href="payment.paymentAction.url" target="_blank" rel="noopener noreferrer">前往支付渠道</a>
          <button v-if="payment.provider === 'LOCAL_MOCK' && payment.status === 'PENDING'" class="button" :disabled="busy" @click="confirmMockPayment">{{ busy ? '正在确认…' : '本地模拟确认付款' }}</button>
          <button v-if="payment.provider !== 'LOCAL_MOCK' && payment.status === 'PENDING' && !payment.paymentAction" class="button" :disabled="busy" @click="payOrder(payment.orderId)">重新获取此订单支付入口</button>
          <p v-if="payment.paymentAction?.type === 'MOCK' && payment.provider !== 'LOCAL_MOCK'" class="inline-error">当前服务端不是 LOCAL_MOCK，本地模拟确认不可用。</p>
          <p class="payment-poll-note" role="status">{{ paymentRefresh ? '正在向服务端查询支付状态…' : paymentPollingFinished ? '自动查询已停止，可手动刷新状态。' : '自动查询不会发起新的付款。' }}</p>
          <div class="button-row"><button class="button button-secondary" @click="refreshPaymentManually">手动查询状态</button><RouterLink class="text-button" to="/account/orders">返回订单</RouterLink></div>
        </section>
      </AsyncState>
    </template>

    <template v-else-if="pageType === 'orders'">
      <div class="page-heading"><p class="eyebrow">会员中心</p><h1>{{ pageTitle }}</h1></div>
      <nav class="status-tabs" aria-label="订单状态筛选"><button v-for="tab in [{ id: '', label: '全部' }, { id: 'PENDING_PAYMENT', label: '待付款' }, { id: 'PAID', label: '待发货' }, { id: 'SHIPPED', label: '已发货' }, { id: 'COMPLETED', label: '已完成' }, { id: 'CANCELLED', label: '已取消' }]" :key="tab.id" :aria-pressed="String(route.query.status ?? '') === tab.id" @click="setOrderFilter(tab.id)">{{ tab.label }}</button></nav>
      <AsyncState :loading="loading" :error="error" :empty="filteredOrders.length === 0" empty-text="没有符合当前筛选的订单。" @retry="load">
        <div class="order-cards"><article v-for="item in filteredOrders" :key="item.id" class="order-card"><div class="order-card-top"><strong>{{ item.shopName }}</strong><span class="status-pill">{{ statusLabel(item.status) }}</span></div><p>订单号 {{ item.orderNo }} · {{ timeText(item.createdAt) }}</p><div class="total-line"><span>{{ statusLabel(item.financialStatus) }}</span><MoneyText :value="item.payableAmount" /></div><div class="button-row"><RouterLink class="button button-secondary" :to="`/account/orders/${item.id}`">查看订单</RouterLink><button v-if="item.status === 'PENDING_PAYMENT'" class="button" @click="payOrder(item.id)">去支付</button></div></article></div>
        <nav v-if="orders && orders.total > orders.pageSize" class="pagination"><button class="button button-secondary" :disabled="orders.page <= 1" @click="router.push({ query: { ...route.query, page: orders.page - 1 } })">上一页</button><span>第 {{ orders.page }} 页，共 {{ orders.total }} 笔</span><button class="button button-secondary" :disabled="orders.page * orders.pageSize >= orders.total" @click="router.push({ query: { ...route.query, page: orders.page + 1 } })">下一页</button></nav>
      </AsyncState>
    </template>

    <template v-else-if="pageType === 'order'">
      <div class="page-heading"><p class="eyebrow">{{ order?.shopName }} · 订单 {{ order?.orderNo }}</p><h1>{{ pageTitle }}</h1></div>
      <AsyncState :loading="loading" :error="error" :empty="!order" empty-text="没有找到这笔订单。" @retry="load">
        <template v-if="order"><p v-if="actionError" class="inline-error" role="alert">{{ fieldErrorMessage(actionError) }}</p>
          <section class="order-status-panel"><span class="status-pill">{{ statusLabel(order.status) }}</span><span class="status-pill status-secondary">{{ statusLabel(order.financialStatus) }}</span><span class="order-amount"><MoneyText :value="order.payableAmount" /></span></section>
          <section class="checkout-panel"><h2>订单商品</h2><article v-for="item in order.items" :key="item.id" class="order-line"><div><strong>{{ item.bookTitle }}</strong><small>ISBN {{ item.isbn }} · {{ item.quantity }} 本 · 已退款 {{ item.refundedQuantity }} 本</small></div><div><MoneyText :value="item.lineAmount" /><small>退款 {{ item.refundedAmount }}</small></div><div class="line-actions"><RouterLink v-if="['PAID','COMPLETED'].includes(order.status)" class="text-button" :to="{ name: 'after-sale-new', query: { orderId: order.id, orderItemId: item.id } }">申请售后</RouterLink><RouterLink v-if="order.status === 'COMPLETED' && item.refundedQuantity < item.quantity" class="text-button" :to="{ name: 'review-new', query: { orderId: order.id, orderItemId: item.id } }">评价此书</RouterLink></div></article></section>
          <section class="checkout-panel"><h2>收货信息快照</h2><p>{{ order.receiver.name }}　{{ order.receiver.phone }}</p><p>{{ order.receiver.address }}</p><p class="muted">地址来自下单快照；之后编辑地址不会更改本订单。</p></section>
          <section v-if="order.shipment" class="checkout-panel"><h2>物流信息 <span class="manual-note">人工录入</span></h2><p>{{ order.shipment.carrier }} · {{ order.shipment.trackingNo }}</p><ol class="timeline"><li v-for="event in order.shipment.events" :key="`${event.eventTime}-${event.description}`"><time>{{ timeText(event.eventTime) }}</time><span>{{ event.description }} <small>（{{ event.source === 'MANUAL' ? '人工录入' : '系统记录' }}）</small></span></li></ol></section>
          <section class="checkout-panel"><h2>订单进度</h2><ol class="timeline"><li v-for="(item, index) in order.timeline" :key="`${item.action}-${index}`"><time>{{ timeText(item.occurredAt) }}</time><span>{{ item.action }}<small v-if="item.reason"> · {{ item.reason }}</small></span></li></ol></section>
          <section v-if="order.status === 'PENDING_PAYMENT' || order.status === 'CLOSING' || order.status === 'SHIPPED'" class="checkout-panel order-actions"><h2>可用操作</h2><button v-if="order.status === 'PENDING_PAYMENT'" class="button" :disabled="busy" @click="payOrder(order.id)">创建或继续此订单支付</button><div v-if="order.status === 'PENDING_PAYMENT'" class="cancel-control"><label>取消原因<input v-model="cancelReason" maxlength="300" /></label><button class="button button-secondary" :disabled="busy || !cancelReason.trim()" @click="cancelOrder">申请取消订单</button></div><p v-if="order.status === 'CLOSING'" role="status">关闭处理中，库存预占会在服务端核实渠道后释放。</p><button v-if="order.status === 'SHIPPED'" class="button" :disabled="busy" @click="confirmReceipt">确认收货</button></section>
        </template>
      </AsyncState>
    </template>

    <template v-else-if="pageType === 'addresses'">
      <div class="page-heading"><p class="eyebrow">会员中心</p><h1>{{ pageTitle }}</h1><p>最多保存 20 个有效地址，编辑地址不会更改已有订单。</p></div>
      <AsyncState :loading="loading" :error="error" :empty="!addresses.length && !addressDraft" empty-text="还没有保存的收货地址。" @retry="load"><template #empty-action><button class="button" @click="editAddress()">添加收货地址</button></template>
        <p v-if="actionError" class="inline-error" role="alert">{{ fieldErrorMessage(actionError) }}</p>
        <div class="address-list"><article v-for="address in addresses" :key="address.id" class="address-card"><div><div class="address-person"><strong>{{ address.receiverName }}</strong><span>{{ address.phone }}</span><span v-if="address.isDefault" class="stock-tag">默认</span></div><p>{{ address.province }}{{ address.city }}{{ address.district }}{{ address.detail }}</p></div><div class="button-row"><button v-if="!address.isDefault" class="text-button" @click="setDefault(address)">设为默认</button><button class="text-button" @click="editAddress(address)">编辑</button><button class="text-button danger-link" @click="deleteAddress(address.id)">删除</button></div></article></div>
        <button v-if="!addressDraft" class="button button-secondary" :disabled="addresses.length >= 20" @click="editAddress()">新增地址</button>
        <form v-if="addressDraft" class="checkout-panel address-form" @submit.prevent="saveAddress"><h2>{{ editingAddressId ? '编辑收货地址' : '新增收货地址' }}</h2>
          <div class="form-grid"><label>收件人<input v-model="addressDraft.receiverName" maxlength="40" required /></label><label>手机号<input v-model="addressDraft.phone" maxlength="32" required /></label><label>省/自治区<input v-model="addressDraft.province" maxlength="40" required /></label><label>城市<input v-model="addressDraft.city" maxlength="40" required /></label><label>区/县<input v-model="addressDraft.district" maxlength="40" required /></label><label class="full-span">详细地址<input v-model="addressDraft.detail" maxlength="200" required /></label></div>
          <label class="checkbox-label"><input v-model="addressDraft.isDefault" type="checkbox" />设为默认地址</label><p v-if="addressMessage" class="inline-error" role="alert">{{ addressMessage }}</p>
          <div class="button-row"><button class="button" :disabled="busy">保存地址</button><button class="button button-secondary" type="button" @click="addressDraft = null">取消</button></div>
        </form>
      </AsyncState>
    </template>

    <template v-else-if="pageType === 'after-sale-new'">
      <div class="page-heading"><p class="eyebrow">按订单行申请 · 退款金额由服务端核对</p><h1>{{ pageTitle }}</h1></div>
      <AsyncState :loading="loading" :error="error" :empty="!selectedOrderItem" empty-text="请从订单详情选择一件商品后发起售后。" @retry="load">
        <template v-if="selectedOrderItem"><section class="checkout-panel"><h2>{{ selectedOrderItem.bookTitle }}</h2><p>订单 {{ order?.orderNo }} · 购买 {{ selectedOrderItem.quantity }} 本 · 已退款 {{ selectedOrderItem.refundedQuantity }} 本</p><p class="helper-note">申请金额来自订单快照，页面不提供任意退款金额输入。售后处理中该订单会暂停发货。</p></section>
          <form class="checkout-panel" @submit.prevent="submitAfterSale"><label>申请类型<select v-model="saleType"><option v-if="order?.status === 'PAID'" value="REFUND_ONLY">未发货退款</option><option v-if="order?.status === 'COMPLETED'" value="RETURN_REFUND">确认收货后退货退款</option></select></label><p v-if="order?.status !== 'PAID' && order?.status !== 'COMPLETED'" class="helper-note">当前订单状态不支持新的售后申请；已发货未收货期间不受理退款。</p><label>申请数量<input v-model.number="saleQuantity" type="number" min="1" :max="selectedOrderItem.quantity - selectedOrderItem.refundedQuantity" required /></label><label>申请原因<textarea v-model="saleReason" maxlength="500" rows="4" required /></label><label>凭证图片（最多 5 张，JPG/PNG/WebP）<input type="file" accept="image/jpeg,image/png,image/webp" multiple @change="chooseEvidence" /></label><p class="field-hint">{{ evidenceFiles.length }} / 5 张已选；文件会通过受权上传接口提交。</p><p v-if="actionError" class="inline-error" role="alert">{{ fieldErrorMessage(actionError) }}</p><button class="button" :disabled="busy || !saleReason.trim() || (order?.status !== 'PAID' && order?.status !== 'COMPLETED')">{{ busy ? '正在提交…' : '提交售后申请' }}</button></form>
        </template>
      </AsyncState>
    </template>

    <template v-else-if="pageType === 'after-sales'">
      <div class="page-heading"><p class="eyebrow">会员中心</p><h1>{{ pageTitle }}</h1></div>
      <AsyncState :loading="loading" :error="error" :empty="!(afterSales?.items.length)" empty-text="还没有售后申请。" @retry="load"><div class="order-cards"><article v-for="item in afterSales?.items" :key="item.id" class="order-card"><div class="order-card-top"><strong>{{ item.afterSaleNo }}</strong><span class="status-pill">{{ statusLabel(item.status) }}</span></div><p>{{ item.type === 'REFUND_ONLY' ? '未发货退款' : '退货退款' }} · {{ item.quantity }} 本</p><div class="total-line"><span>申请金额（服务端快照）</span><MoneyText :value="item.requestedAmount" /></div><RouterLink class="button button-secondary" :to="`/account/after-sales/${item.id}`">查看进度</RouterLink></article></div></AsyncState>
    </template>

    <template v-else-if="pageType === 'after-sale'">
      <div class="page-heading"><p class="eyebrow">会员中心</p><h1>{{ pageTitle }}</h1></div>
      <AsyncState :loading="loading" :error="error" :empty="!afterSale" empty-text="没有找到这笔售后。" @retry="load"><template v-if="afterSale"><p v-if="actionError" class="inline-error" role="alert">{{ fieldErrorMessage(actionError) }}</p>
        <section class="order-status-panel"><span class="status-pill">{{ statusLabel(afterSale.status) }}</span><span>{{ afterSale.afterSaleNo }}</span><MoneyText :value="afterSale.requestedAmount" /></section>
        <section class="checkout-panel"><h2>申请信息</h2><p>{{ afterSale.type === 'REFUND_ONLY' ? '未发货退款' : '退货退款' }} · {{ afterSale.quantity }} 件</p><p>原因：{{ afterSale.reason }}</p><p v-if="afterSale.reviewNote">审核说明：{{ afterSale.reviewNote }}</p><p v-if="afterSale.returnDeadline">退货截止：{{ timeText(afterSale.returnDeadline) }}</p><p v-if="afterSale.returnCarrier">退货物流：{{ afterSale.returnCarrier }} · {{ afterSale.returnTrackingNo }}</p><p v-if="afterSale.refund">退款 {{ afterSale.refund.refundNo }} · {{ statusLabel(afterSale.refund.status) }}</p></section>
        <form v-if="afterSale.status === 'APPROVED' && afterSale.type === 'RETURN_REFUND' && !afterSale.returnTrackingNo" class="checkout-panel" @submit.prevent="sendReturnShipment"><h2>填写退货运单</h2><label>承运方<input v-model="returnDraft.carrier" maxlength="50" required /></label><label>运单号<input v-model="returnDraft.trackingNo" maxlength="80" required /></label><button class="button">提交运单</button></form>
        <button v-if="afterSale.status === 'REQUESTED'" class="button button-secondary" :disabled="busy" @click="cancelAfterSale">撤销售后申请</button>
      </template></AsyncState>
    </template>

    <template v-else-if="pageType === 'review-new'">
      <div class="page-heading"><p class="eyebrow">订单 {{ order?.orderNo }}</p><h1>{{ pageTitle }}</h1><p>确认收货后 30 天内可评价；已全额退款订单行不可评价。</p></div>
      <AsyncState :loading="loading" :error="error" :empty="!selectedOrderItem" empty-text="请从符合条件的订单行进入评价。" @retry="load"><template v-if="selectedOrderItem"><form class="checkout-panel review-form" @submit.prevent="submitReview"><h2>{{ selectedOrderItem.bookTitle }}</h2><p>已退款 {{ selectedOrderItem.refundedQuantity }} / {{ selectedOrderItem.quantity }} 本</p><label>评分<select v-model.number="reviewDraft.rating"><option :value="5">5 星 · 很喜欢</option><option :value="4">4 星 · 推荐</option><option :value="3">3 星 · 一般</option><option :value="2">2 星 · 不太喜欢</option><option :value="1">1 星 · 不推荐</option></select></label><label>阅读感受<textarea v-model="reviewDraft.content" maxlength="1000" rows="6" required /></label><p class="field-hint">纯文本评价，提交后不可编辑或删除。</p><p v-if="actionError" class="inline-error" role="alert">{{ fieldErrorMessage(actionError) }}</p><button class="button" :disabled="busy">提交评价</button></form></template></AsyncState>
    </template>

    <template v-else-if="pageType === 'security'">
      <div class="page-heading"><p class="eyebrow">会员中心</p><h1>{{ pageTitle }}</h1></div>
      <AsyncState :loading="loading" :error="error" :empty="!currentUser" @retry="load"><template v-if="currentUser"><section class="checkout-panel identity-card"><h2>当前身份</h2><p>{{ currentUser.nickname }}（{{ currentUser.username }}）</p><p class="muted">{{ currentUser.roles.join(' · ') }}</p><RouterLink to="/account/orders">返回会员中心</RouterLink></section><form class="checkout-panel password-form" @submit.prevent="changePassword"><h2>修改密码</h2><label>当前密码<input v-model="passwordDraft.oldPassword" type="password" autocomplete="current-password" required /></label><label>新密码<input v-model="passwordDraft.newPassword" type="password" autocomplete="new-password" maxlength="72" required /></label><label>确认新密码<input v-model="passwordDraft.confirmPassword" type="password" autocomplete="new-password" maxlength="72" required /></label><p class="field-hint">新密码为 12—72 个 UTF-8 字节。成功修改后所有会话退出，需要重新登录。</p><p v-if="actionError" class="inline-error" role="alert">{{ fieldErrorMessage(actionError) }}</p><button class="button" :disabled="busy">更新密码并重新登录</button></form><button class="text-button danger-link" :disabled="busy" @click="session.logout">退出当前会话</button></template></AsyncState>
    </template>
  </section>
</template>
