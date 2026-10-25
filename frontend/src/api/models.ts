import type { ApiId, ApiMoney } from './generated'

export type Id = ApiId
export type Money = ApiMoney
export type RoleCode = 'MEMBER' | 'MERCHANT' | 'WAREHOUSE' | 'OPERATOR' | 'SUPPORT' | 'FINANCE' | 'ADMIN'
export type ShopDuty = 'MERCHANT' | 'WAREHOUSE'
export type UserView = {
  id: Id; username: string; nickname: string; status: 'ACTIVE' | 'DISABLED'
  roles: RoleCode[]; permissions: string[]; shops: Array<{ id: Id; name: string; duties: ShopDuty[] }>
}
export type AuthView = { accessToken: string; expiresIn: 900; csrfToken: string; user: UserView }
export type Page<T> = { items: T[]; page: number; pageSize: number; total: number }
export type CategoryView = { id: Id; parentId: Id | null; name: string; sortOrder: number; status: 'ACTIVE' | 'DISABLED'; children: CategoryView[] }
export type BookView = {
  id: Id; isbn: string; title: string; author: string; publisher: string; publicationDate: string | null
  categoryId: Id; description: string; coverFileId: Id | null; status: 'ACTIVE' | 'DISABLED'
  categoryName: string; coverUrl: string | null
}
export type ProductView = {
  id: Id; shopId: Id; shopName: string; book: BookView; skuId: Id; skuCode: string; salePrice: Money
  availableQuantity: number; status: 'DRAFT' | 'ON_SALE' | 'OFF_SHELF'; featured: boolean; version: number; skuVersion: number
}
export type ReviewView = { id: Id; productId: Id; rating: number; content: string; nickname: string; createdAt: string; status: 'VISIBLE' | 'HIDDEN' }
export type AddressView = {
  id: Id; receiverName: string; phone: string; province: string; city: string; district: string; detail: string; isDefault: boolean
}
export type CartItemView = {
  id: Id; skuId: Id; productId: Id; title: string; coverUrl: string | null; salePrice: Money
  quantity: number; selected: boolean; availableQuantity: number; purchasable: boolean; unavailableReason: string | null
}
export type CartView = { groups: Array<{ shopId: Id; shopName: string; items: CartItemView[] }> }
export type PreviewView = {
  previewId: Id; expiresAt: string; address: AddressView
  shops: Array<{ shopId: Id; shopName: string; items: Array<{ skuId: Id; title: string; quantity: number; unitPrice: Money; lineAmount: Money }>; goodsAmount: Money; freightAmount: Money; payableAmount: Money }>
  totalAmount: Money
}
export type OrderSummary = {
  id: Id; orderNo: string; shopId: Id; shopName: string; status: string; financialStatus: string
  payableAmount: Money; refundedAmount: Money; expiresAt: string; createdAt: string; itemCount: number
}
export type OrderView = OrderSummary & {
  items: Array<{ id: Id; skuId: Id; bookTitle: string; isbn: string; unitPrice: Money; quantity: number; lineAmount: Money; refundedQuantity: number; refundedAmount: Money }>
  receiver: { name: string; phone: string; address: string }
  paidAt?: string | null; shippedAt?: string | null; completedAt?: string | null; closedAt?: string | null
  shipment?: { id: Id; carrier: string; trackingNo: string; shippedAt: string; receivedAt?: string | null; events: Array<{ eventTime: string; description: string; source: 'MANUAL' | 'SYSTEM' }> } | null
  timeline: Array<{ status: string; action: string; occurredAt: string; reason: string }>; version: number
}
export type BatchView = { batchId: Id; batchNo: string; totalAmount: Money; orders: OrderSummary[] }
export type PaymentView = {
  id: Id; paymentNo: string; orderId: Id; provider: string; status: 'INIT' | 'PENDING' | 'SUCCEEDED' | 'CLOSED'
  amount: Money; currency: 'CNY'; paidAt?: string | null
  paymentAction?: { type: 'REDIRECT' | 'MOCK'; url?: string | null; expiresAt?: string | null } | null
}
export type AfterSaleView = {
  id: Id; afterSaleNo: string; orderId: Id; orderItemId: Id; type: 'REFUND_ONLY' | 'RETURN_REFUND'; quantity: number
  requestedAmount: Money; reason: string; status: string; evidenceFileIds: Id[]; reviewNote?: string | null
  returnDeadline?: string | null; returnCarrier?: string | null; returnTrackingNo?: string | null; restockQuantity: number
  refund?: { id: Id; refundNo: string; status: string; amount: Money } | null; createdAt: string
}
export type ShipmentView = { id: Id; carrier: string; trackingNo: string; shippedAt: string; receivedAt?: string | null; items: Array<{ orderItemId: Id; shippedQuantity: number }>; events: Array<{ eventTime: string; description: string; source: string }> }
export type InventoryView = { skuId: Id; onHand: number; reserved: number; available: number; version: number }
export type RefundView = { id: Id; refundNo: string; afterSaleId?: Id | null; paymentId: Id; reasonType: string; status: string; amount: Money; failureCode?: string | null; succeededAt?: string | null }
export type ReconciliationView = { id: Id; provider: string; billDate: string; status: string; checkedCount: number; differenceCount: number; completedAt?: string | null }
export type IssueView = { id: Id; issueType: string; orderId?: Id | null; paymentId?: Id | null; refundId?: Id | null; expectedAmount?: Money | null; actualAmount?: Money | null; status: string; resolution?: string | null; createdAt: string }
export type SettlementView = {
  id: Id; settlementNo: string; shopId: Id; periodDate: string; status: string; grossAmount: Money; refundAmount: Money
  feeAmount: Money; adjustmentAmount: Money; payableAmount: Money; createdBy: Id; approvedBy?: Id | null; paidAt?: string | null
  mockPayoutNo?: string | null; version: number; items?: Array<{ orderId: Id; goodsAmount: Money; refundAmount: Money; netAmount: Money; feeAmount: Money; payableAmount: Money }>
  adjustments?: Array<{ id: Id; amount: Money; reason: string }>
}
export type ApiRow = Record<string, unknown>
