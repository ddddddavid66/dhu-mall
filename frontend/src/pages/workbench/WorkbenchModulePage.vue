<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElDialog } from 'element-plus'
import { useRoute } from 'vue-router'
import AsyncState from '../../components/AsyncState.vue'
import MoneyText from '../../components/MoneyText.vue'
import { ApiError, callOperation, downloadFile } from '../../api/client'
import type { ApiOperationId } from '../../api/generated'
import type { ApiRow, CategoryView, Page } from '../../api/models'
import { useSessionStore } from '../../stores/session'
import { useWorkbenchStore } from '../../stores/workbench'

type FieldValue = string | number | boolean | string[]
type FieldSpec = { name: string; label: string; type?: 'text' | 'password' | 'number' | 'date' | 'datetime-local' | 'textarea' | 'select' | 'checkbox'; required?: boolean; maxLength?: number; options?: Array<{ value: string; label: string }>; multiple?: boolean; hint?: string }
type ActionName = 'book-create'|'book-edit'|'category-create'|'category-edit'|'product-create'|'product-edit'|'product-status'|'inventory-adjust'|'order-ship'|'shipment-event'|'sale-decision'|'return-inspection'|'refund-execute'|'reconciliation-start'|'issue-resolve'|'settlement-create'|'settlement-decision'|'settlement-payout'|'adjustment-create'|'adjustment-approve'|'product-moderate'|'review-visibility'|'user-create'|'user-edit'|'role-create'|'role-edit'|'shop-create'|'shop-edit'|'member-save'
type Column = { key: string; label: string; money?: boolean }

const route = useRoute()
const API_VERSION_ACTIONS = new Set<string>(['product-edit','product-status','inventory-adjust','order-ship','settlement-decision','settlement-payout','product-moderate'])
const session = useSessionStore()
const workbench = useWorkbenchStore()
const loading = ref(false)
const error = ref<unknown>(null)
const actionError = ref<unknown>(null)
const rows = ref<ApiRow[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = 20
const search = ref('')
const filterStatus = ref('')
const shopFilter = ref('')
const actorFilter = ref('')
const actionFilter = ref('')
const traceFilter = ref('')
const fromFilter = ref('')
const toFilter = ref('')
const subtab = ref('')
const activeAction = ref<ActionName | null>(null)
const actionRow = ref<ApiRow | null>(null)
const formValues = ref<Record<string, FieldValue>>({})
const actionOpen = ref(false)
const actionBusy = ref(false)
const detailOpen = ref(false)
const detailBusy = ref(false)
const detailTitle = ref('')
const detail = ref<ApiRow | null>(null)
const detailEvidence = ref<string[]>([])
const evidencePreview = ref('')
const evidenceLoading = ref(false)
const selectedFile = ref<File | null>(null)
const memberOpen = ref(false)
const memberBusy = ref(false)
const memberShop = ref<ApiRow | null>(null)
const members = ref<ApiRow[]>([])
const users = ref<ApiRow[]>([])
const roles = ref<ApiRow[]>([])
const permissions = ref<ApiRow[]>([])
const books = ref<ApiRow[]>([])
const categories = ref<CategoryView[]>([])
const settlements = ref<ApiRow[]>([])
const issues = ref<ApiRow[]>([])
const statusMessage = ref('')
let activeController: AbortController | null = null
let evidenceUrl: string | null = null

const memberTable = computed(() => route.path === '/workbench/admin/users' && subtab.value === 'roles')
const moderationReviews = computed(() => route.path === '/workbench/ops/moderation' && subtab.value === 'reviews')
const issueTable = computed(() => route.path === '/workbench/finance/reconciliation' && subtab.value === 'issues')
const pageLabel = computed(() => String(route.meta.title ?? '业务记录'))
const filterStatuses = computed(() => {
  if (route.path.includes('/orders')) return [['PENDING_PAYMENT','待付款'],['CLOSING','关闭处理中'],['PAID','待发货'],['SHIPPED','已发货'],['COMPLETED','已完成'],['CANCELLED','已取消'],['CLOSED_REFUND','退款关闭']]
  if (route.path.includes('after-sales') || route.path.includes('returns')) return [['REQUESTED','待审核'],['APPROVED','已批准'],['RETURNING','等待退回'],['RECEIVED','已收货待验收'],['REFUNDING','退款处理中'],['COMPLETED','已完成'],['REJECTED','已拒绝'],['EXPIRED','已过期']]
  if (route.path.includes('refunds')) return [['CREATED','待执行'],['PROCESSING','处理中'],['SUCCEEDED','成功'],['FAILED','失败']]
  if (route.path.includes('settlements')) return [['DRAFT','草稿'],['APPROVED','已批准'],['PAID','已模拟出款'],['REJECTED','已拒绝']]
  if (route.path.includes('reconciliation')) return [['PENDING','等待处理'],['RUNNING','处理中'],['SUCCEEDED','成功'],['FAILED','失败'],['OPEN','待处理'],['RESOLVED','已解决']]
  if (route.path.includes('products') || route.path.includes('books') || route.path.includes('shops') || memberTable.value) return [['DRAFT','草稿'],['ON_SALE','在售'],['OFF_SHELF','已下架'],['ACTIVE','启用'],['DISABLED','停用'],['SUSPENDED','暂停营业']]
  return []
})

const resourceColumns: Record<string, Column[]> = {
  '/workbench/catalog/books': [{key:'isbn',label:'ISBN'},{key:'title',label:'书名'},{key:'author',label:'作者'},{key:'publisher',label:'出版社'},{key:'categoryName',label:'分类'},{key:'status',label:'状态'}],
  '/workbench/catalog/categories': [{key:'name',label:'分类名称'},{key:'parentName',label:'上级分类'},{key:'sortOrder',label:'顺序'},{key:'status',label:'状态'}],
  '/workbench/merchant/products': [{key:'book.title',label:'书名'},{key:'skuCode',label:'SKU'},{key:'salePrice',label:'售价',money:true},{key:'availableQuantity',label:'可售库存'},{key:'status',label:'状态'},{key:'version',label:'版本'}],
  '/workbench/merchant/inventory': [{key:'skuId',label:'SKU 编号'},{key:'onHand',label:'实物量'},{key:'reserved',label:'预占量'},{key:'available',label:'可用量'},{key:'version',label:'版本'}],
  '/workbench/merchant/orders': [{key:'orderNo',label:'订单号'},{key:'shopName',label:'店铺'},{key:'status',label:'订单状态'},{key:'financialStatus',label:'财务状态'},{key:'payableAmount',label:'应付',money:true},{key:'createdAt',label:'下单时间'}],
  '/workbench/support/after-sales': [{key:'afterSaleNo',label:'售后单号'},{key:'type',label:'类型'},{key:'status',label:'售后状态'},{key:'quantity',label:'数量'},{key:'requestedAmount',label:'申请金额',money:true},{key:'createdAt',label:'申请时间'}],
  '/workbench/warehouse/returns': [{key:'afterSaleNo',label:'售后单号'},{key:'returnCarrier',label:'承运方'},{key:'returnTrackingNo',label:'退货运单'},{key:'status',label:'状态'},{key:'requestedAmount',label:'退款金额',money:true}],
  '/workbench/finance/refunds': [{key:'refundNo',label:'退款单号'},{key:'reasonType',label:'类型'},{key:'status',label:'退款状态'},{key:'amount',label:'金额',money:true},{key:'failureCode',label:'失败原因'},{key:'succeededAt',label:'成功时间'}],
  '/workbench/finance/reconciliation': [{key:'provider',label:'渠道'},{key:'billDate',label:'账期'},{key:'status',label:'状态'},{key:'checkedCount',label:'核对笔数'},{key:'differenceCount',label:'差异笔数'}],
  '/workbench/finance/settlements': [{key:'settlementNo',label:'结算单号'},{key:'shopId',label:'店铺编号'},{key:'periodDate',label:'账期'},{key:'status',label:'状态'},{key:'grossAmount',label:'商品额',money:true},{key:'refundAmount',label:'退款',money:true},{key:'feeAmount',label:'手续费',money:true},{key:'adjustmentAmount',label:'调整',money:true},{key:'payableAmount',label:'应付',money:true}],
  '/workbench/finance/adjustments': [{key:'id',label:'调整编号'},{key:'originalSettlementId',label:'原结算单'},{key:'orderId',label:'订单'},{key:'issueId',label:'差异'},{key:'amount',label:'调整金额',money:true},{key:'status',label:'状态'}],
  '/workbench/merchant/settlements': [{key:'settlementNo',label:'结算单号'},{key:'periodDate',label:'账期'},{key:'status',label:'状态'},{key:'grossAmount',label:'商品额',money:true},{key:'refundAmount',label:'退款',money:true},{key:'feeAmount',label:'手续费',money:true},{key:'payableAmount',label:'应付',money:true}],
  '/workbench/ops/moderation': [{key:'book.title',label:'书名'},{key:'shopName',label:'店铺'},{key:'salePrice',label:'售价',money:true},{key:'status',label:'状态'},{key:'featured',label:'精选'},{key:'version',label:'版本'}],
  '/workbench/admin/users': [{key:'username',label:'用户名'},{key:'nickname',label:'昵称'},{key:'roles',label:'角色'},{key:'status',label:'状态'}],
  '/workbench/admin/shops': [{key:'name',label:'店铺名称'},{key:'contactName',label:'联系人'},{key:'contactPhone',label:'联系电话'},{key:'status',label:'状态'},{key:'createdAt',label:'创建时间'}],
  '/workbench/admin/audit': [{key:'createdAt',label:'时间'},{key:'actorId',label:'操作者 ID'},{key:'action',label:'操作'},{key:'resourceType',label:'资源类型'},{key:'result',label:'结果'},{key:'traceId',label:'跟踪号'},{key:'summary',label:'脱敏摘要'}],
}

const columns = computed<Column[]>(() => {
  if (memberTable.value) return [{key:'code',label:'角色代码'},{key:'name',label:'名称'},{key:'status',label:'状态'},{key:'permissionIds',label:'授权数量'}]
  if (moderationReviews.value) return [{key:'productId',label:'商品编号'},{key:'nickname',label:'读者'},{key:'rating',label:'评分'},{key:'content',label:'评价内容'},{key:'status',label:'可见状态'},{key:'createdAt',label:'评价时间'}]
  if (issueTable.value) return [{key:'issueType',label:'差异类型'},{key:'orderId',label:'订单'},{key:'paymentId',label:'支付'},{key:'refundId',label:'退款'},{key:'expectedAmount',label:'应有金额',money:true},{key:'actualAmount',label:'实际金额',money:true},{key:'status',label:'状态'}]
  return resourceColumns[route.path] ?? []
})

function record(value: unknown): value is ApiRow { return typeof value === 'object' && value !== null && !Array.isArray(value) }
function readField(value: unknown, path: string): unknown { return path.split('.').reduce<unknown>((current, part) => record(current) ? current[part] : undefined, value) }
function idOf(row: ApiRow) { return String(row.id ?? '') }
function visibleText(value: unknown): string {
  if (value === null || value === undefined || value === '') return '—'
  if (typeof value === 'boolean') return value ? '是' : '否'
  if (Array.isArray(value)) return value.map(item => record(item) ? String(item.name ?? item.code ?? item.id ?? '') : String(item)).filter(Boolean).join('、') || '—'
  if (record(value)) return JSON.stringify(value)
  if (typeof value === 'string' && /^\d{4}-\d\d-\d\dT/.test(value)) return new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false })
  if (typeof value === 'string' && /^[A-Z_]+$/.test(value)) return statusLabels[value] ?? value
  return String(value)
}
const statusLabels: Record<string, string> = {
  DRAFT:'草稿',ON_SALE:'在售',OFF_SHELF:'已下架',ACTIVE:'启用',DISABLED:'停用',SUSPENDED:'暂停营业',
  PENDING_PAYMENT:'待付款',CLOSING:'关闭处理中',CANCELLED:'已取消',PAID:'已付款',SHIPPED:'已发货',COMPLETED:'已完成',CLOSED_REFUND:'退款关闭',
  UNPAID:'未付款',PARTIALLY_REFUNDED:'部分退款',REFUNDED:'已退款',REQUESTED:'待审核',APPROVED:'已批准',RETURNING:'等待退回',RECEIVED:'待验收',REFUNDING:'退款处理中',REJECTED:'已拒绝',EXPIRED:'已过期',
  CREATED:'待执行',PROCESSING:'处理中',SUCCEEDED:'成功',FAILED:'失败',PENDING:'待处理',RUNNING:'执行中',OPEN:'待解决',RESOLVED:'已解决',VISIBLE:'公开',HIDDEN:'已隐藏',SUCCESS:'成功',DENIED:'拒绝',
}

const isShopPage = computed(() => ['/workbench/merchant/products','/workbench/merchant/inventory','/workbench/merchant/orders','/workbench/warehouse/returns','/workbench/merchant/settlements'].includes(route.path))
const listOperation = computed<ApiOperationId>(() => {
  if (memberTable.value) return 'API-73'
  if (moderationReviews.value) return 'API-91'
  if (issueTable.value) return 'API-56'
  const entries: Record<string, ApiOperationId> = {
    '/workbench/catalog/books':'API-63','/workbench/catalog/categories':'API-92','/workbench/merchant/products':'API-15','/workbench/merchant/inventory':'API-19',
    '/workbench/merchant/orders':'API-38','/workbench/support/after-sales':'API-47','/workbench/warehouse/returns':'API-50','/workbench/finance/refunds':'API-52',
    '/workbench/finance/reconciliation':'API-54','/workbench/finance/settlements':'API-58','/workbench/finance/adjustments':'API-82','/workbench/merchant/settlements':'API-62',
    '/workbench/ops/moderation':'API-90','/workbench/admin/users':'API-70','/workbench/admin/shops':'API-84','/workbench/admin/audit':'API-77',
  }
  return entries[route.path] ?? 'API-89'
})

function categoryRows(items: CategoryView[], parentName = ''): ApiRow[] {
  return items.flatMap(item => [{ ...item, parentName: parentName || '—' }, ...categoryRows(item.children ?? [], item.name)])
}
function queryValues(): Record<string, string | number | boolean | null | undefined> {
  const query: Record<string, string | number | boolean | null | undefined> = { page: page.value, pageSize }
  if (search.value.trim()) {
    if (route.path === '/workbench/ops/moderation' && moderationReviews.value) query.productId = search.value.trim()
    else if (route.path === '/workbench/merchant/orders') query.orderNo = search.value.trim()
    else if (route.path !== '/workbench/merchant/inventory') query.keyword = search.value.trim()
  }
  if (filterStatus.value && !(route.path === '/workbench/finance/reconciliation' && !issueTable.value)) query.status = filterStatus.value
  if (['/workbench/finance/refunds','/workbench/finance/reconciliation','/workbench/finance/settlements','/workbench/finance/adjustments','/workbench/support/after-sales'].includes(route.path) && shopFilter.value.trim()) query.shopId = shopFilter.value.trim()
  if (route.path === '/workbench/admin/audit') {
    if (actorFilter.value.trim()) query.actorId = actorFilter.value.trim()
    if (actionFilter.value.trim()) query.action = actionFilter.value.trim()
    if (traceFilter.value.trim()) query.traceId = traceFilter.value.trim()
    if (fromFilter.value) query.from = new Date(fromFilter.value).toISOString()
    if (toFilter.value) query.to = new Date(toFilter.value).toISOString()
  }
  return query
}

async function load() {
  activeController?.abort()
  const controller = new AbortController()
  activeController = controller
  loading.value = true
  error.value = null
  statusMessage.value = ''
  try {
    if (route.path === '/workbench/admin/audit' && fromFilter.value && toFilter.value && (new Date(toFilter.value).getTime() - new Date(fromFilter.value).getTime()) > 90 * 86400000) throw new Error('审计查询时间范围最多 90 天。')
    if (isShopPage.value && !workbench.selectedShopId) throw new Error('此账号没有由服务端返回的店铺范围。')
    const path = isShopPage.value ? { shopId: workbench.selectedShopId ?? '' } : undefined
    const result = await callOperation<unknown>(listOperation.value, { path, query: queryValues(), signal: controller.signal, shopScoped: isShopPage.value })
    if (route.path === '/workbench/catalog/categories') {
      rows.value = Array.isArray(result) ? categoryRows(result as CategoryView[]) : []
      total.value = rows.value.length
    } else {
      const payload = record(result) ? result : null
      const list = Array.isArray(result) ? result : payload && Array.isArray(payload.items) ? payload.items : []
      rows.value = list.filter(record)
      total.value = payload && typeof payload.total === 'number' ? payload.total : rows.value.length
      if (payload && typeof payload.page === 'number') page.value = payload.page
    }
    if (route.path === '/workbench/finance/reconciliation' && !issueTable.value) subtab.value = 'runs'
  } catch (cause) {
    if (!(cause instanceof DOMException && cause.name === 'AbortError')) error.value = cause
  } finally { if (!controller.signal.aborted) loading.value = false }
}

function clearFilters() { search.value = ''; filterStatus.value = ''; shopFilter.value = ''; actorFilter.value = ''; actionFilter.value = ''; traceFilter.value = ''; fromFilter.value = ''; toFilter.value = ''; page.value = 1; void load() }
function applyFilters() { page.value = 1; void load() }
function changePage(next: number) { page.value = next; void load() }

function plusDaysInShanghai(daysAgo: number) {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(Date.now() - daysAgo * 86400000)
  const part = (type: string) => parts.find(item => item.type === type)?.value ?? ''
  return `${part('year')}-${part('month')}-${part('day')}`
}

function options(items: ApiRow[], valueKey: string, labelKey: string): Array<{value:string;label:string}> {
  return items.map(item => ({ value: String(readField(item, valueKey) ?? ''), label: String(readField(item, labelKey) ?? '') })).filter(item => item.value && item.label)
}

function categoryDescendants(targetId: string, items: CategoryView[]): string[] {
  for (const item of items) {
    if (item.id === targetId) return [item.id, ...item.children.flatMap(child => categoryDescendants(child.id, [child]))]
    const nested = categoryDescendants(targetId, item.children)
    if (nested.length) return nested
  }
  return []
}

const fields = computed<FieldSpec[]>(() => {
  const currentCategoryId = idOf(actionRow.value ?? {})
  const excludedParents = activeAction.value === 'category-edit' ? categoryDescendants(currentCategoryId, categories.value) : []
  const categoryOptions = options(categoryRows(categories.value).filter(row => row.status === 'ACTIVE'), 'id', 'name')
  const parentOptions = categories.value.filter(item => item.status === 'ACTIVE' && !excludedParents.includes(item.id)).map(item => ({ value:item.id, label:item.name }))
  const bookOptions = options(books.value, 'id', 'title')
  const roleOptions = options(roles.value, 'id', 'name')
  const permissionOptions = options(permissions.value, 'id', 'name')
  switch (activeAction.value) {
    case 'book-create': case 'book-edit': return [
      {name:'isbn',label:'ISBN',required:true,maxLength:17},{name:'title',label:'书名',required:true,maxLength:200},{name:'author',label:'作者',required:true,maxLength:200},{name:'publisher',label:'出版社',required:true,maxLength:120},
      {name:'publicationDate',label:'出版日期',type:'date'},{name:'categoryId',label:'分类',type:'select',required:true,options:categoryOptions},{name:'description',label:'内容简介',type:'textarea',maxLength:5000},{name:'status',label:'资料状态',type:'select',required:true,options:[{value:'ACTIVE',label:'启用'},{value:'DISABLED',label:'停用'}]},
    ]
    case 'category-create': case 'category-edit': return [
      {name:'name',label:'分类名称',required:true,maxLength:50},{name:'parentId',label:'上级分类（仅两级）',type:'select',options:[{value:'',label:'一级分类'},...parentOptions]},{name:'sortOrder',label:'排序值',type:'number',required:true},{name:'status',label:'状态',type:'select',required:true,options:[{value:'ACTIVE',label:'启用'},{value:'DISABLED',label:'停用'}]},
    ]
    case 'product-create': return [
      {name:'bookId',label:'公共书目',type:'select',required:true,options:bookOptions},{name:'skuCode',label:'SKU 编码',required:true,maxLength:64},{name:'salePrice',label:'售价（人民币，两位小数）',required:true,hint:'金额按契约作为十进制字符串发送。'},{name:'description',label:'商品介绍',type:'textarea',maxLength:5000},
    ]
    case 'product-edit': return [{name:'salePrice',label:'售价（人民币，两位小数）',required:true},{name:'description',label:'商品介绍',type:'textarea',maxLength:5000}]
    case 'product-status': return [{name:'status',label:'商品状态',type:'select',required:true,options:[{value:'ON_SALE',label:'上架'},{value:'OFF_SHELF',label:'商家下架'}]}]
    case 'inventory-adjust': return [{name:'delta',label:'实物库存增减量（可为负）',type:'number',required:true,hint:'只调整实物数量；预占量由交易服务管理。'},{name:'reason',label:'调整原因',type:'textarea',required:true,maxLength:300}]
    case 'order-ship': return [{name:'carrier',label:'承运方',required:true,maxLength:50},{name:'trackingNo',label:'物流单号',required:true,maxLength:80}]
    case 'shipment-event': return [{name:'eventTime',label:'轨迹时间（本地）',type:'datetime-local',required:true},{name:'description',label:'物流描述',type:'textarea',required:true,maxLength:300}]
    case 'sale-decision': return [{name:'approved',label:'审核结果',type:'select',required:true,options:[{value:'true',label:'同意申请'},{value:'false',label:'拒绝申请'}]},{name:'note',label:'审核说明',type:'textarea',required:true,maxLength:500}]
    case 'return-inspection': return [{name:'accepted',label:'退货验收',type:'select',required:true,options:[{value:'true',label:'符合申请'},{value:'false',label:'退货不符'}]},{name:'restock',label:'可再售并回补库存',type:'checkbox',hint:'仅验收合格时可选择；退款成功不再次回补。'},{name:'note',label:'验收说明',type:'textarea',required:true,maxLength:500}]
    case 'reconciliation-start': return [{name:'billDate',label:'账单日期',type:'date',required:true},{name:'provider',label:'渠道',type:'select',required:true,options:[{value:'ALIPAY_SANDBOX',label:'支付宝沙箱'},{value:'LOCAL_MOCK',label:'本地模拟'}]}]
    case 'issue-resolve': return [{name:'resolution',label:'处理说明',type:'textarea',required:true,maxLength:1000},{name:'evidenceReference',label:'证据编号或引用',required:true,maxLength:255}]
    case 'settlement-create': return [{name:'shopId',label:'店铺 ID',required:true,hint:'使用已有结算记录中的服务端店铺 ID。接口会再次校验权限与资格。'},{name:'periodDate',label:'已结束的北京时间账期',type:'date',required:true}]
    case 'settlement-decision': return [{name:'approved',label:'审核结果',type:'select',required:true,options:[{value:'true',label:'批准'},{value:'false',label:'拒绝'}]},{name:'note',label:'审核说明',type:'textarea',required:true,maxLength:500}]
    case 'adjustment-create': return [{name:'originalSettlementId',label:'原结算单',type:'select',required:true,options:options(settlements.value,'id','settlementNo')},{name:'issueId',label:'未解决差异',type:'select',required:true,options:options(issues.value,'id','issueType')},{name:'orderId',label:'关联订单 ID',required:true},{name:'amount',label:'调整金额（人民币，两位小数，可负）',required:true},{name:'reason',label:'调整理由',type:'textarea',required:true,maxLength:500}]
    case 'adjustment-approve': return [{name:'note',label:'复核说明',type:'textarea',required:true,maxLength:300}]
    case 'product-moderate': return [{name:'featured',label:'设为精选',type:'checkbox'},{name:'reason',label:'运营说明',type:'textarea',required:true,maxLength:300,hint:'本操作只可下架违规商品，不会替商家强制上架。'}]
    case 'review-visibility': return [{name:'status',label:'评价可见性',type:'select',required:true,options:[{value:'VISIBLE',label:'公开'},{value:'HIDDEN',label:'隐藏'}]},{name:'reason',label:'审核原因',type:'textarea',required:true,maxLength:300}]
    case 'user-create': return [{name:'username',label:'登录用户名',required:true,maxLength:32},{name:'nickname',label:'昵称',required:true,maxLength:40},{name:'initialPassword',label:'初始密码（仅本次提交）',type:'password',required:true,maxLength:72,hint:'必须符合后端密码策略；密码不会写入页面存储。'},{name:'roleIds',label:'角色',type:'select',multiple:true,required:true,options:roleOptions}]
    case 'user-edit': return [{name:'nickname',label:'昵称',required:true,maxLength:40},{name:'status',label:'账号状态',type:'select',required:true,options:[{value:'ACTIVE',label:'启用'},{value:'DISABLED',label:'禁用'}]},{name:'roleIds',label:'角色',type:'select',multiple:true,required:true,options:roleOptions}]
    case 'role-create': return [{name:'code',label:'角色代码',required:true,maxLength:32},{name:'name',label:'角色名称',required:true,maxLength:50},{name:'permissionIds',label:'动作权限',type:'select',multiple:true,required:true,options:permissionOptions}]
    case 'role-edit': return [{name:'name',label:'角色名称',required:true,maxLength:50},{name:'status',label:'角色状态',type:'select',required:true,options:[{value:'ACTIVE',label:'启用'},{value:'DISABLED',label:'停用'}]},{name:'permissionIds',label:'动作权限',type:'select',multiple:true,required:true,options:permissionOptions}]
    case 'shop-create': case 'shop-edit': return [{name:'name',label:'店铺名称',required:true,maxLength:80},{name:'contactName',label:'联系人',required:true,maxLength:40},{name:'contactPhone',label:'联系电话',required:true,maxLength:32},{name:'status',label:'店铺状态',type:'select',required:true,options:[{value:'ACTIVE',label:'营业'},{value:'SUSPENDED',label:'暂停营业'}]}]
    case 'member-save': return [{name:'userId',label:'成员用户',type:'select',required:true,options:options(users.value,'id','username')},{name:'duty',label:'店铺职责',type:'select',required:true,options:[{value:'MERCHANT',label:'商家'},{value:'WAREHOUSE',label:'仓库'}]},{name:'status',label:'成员状态',type:'select',required:true,options:[{value:'ACTIVE',label:'启用'},{value:'DISABLED',label:'停用'}]}]
    default: return []
  }
})

const actionTitles: Record<ActionName, string> = {
  'book-create':'新建公共书目','book-edit':'编辑公共书目','category-create':'新建分类','category-edit':'编辑分类','product-create':'创建本店商品','product-edit':'编辑商品与价格','product-status':'变更商品状态','inventory-adjust':'人工库存调整',
  'order-ship':'整体发货','shipment-event':'录入物流轨迹','sale-decision':'客服审核售后','return-inspection':'仓库验收退货','refund-execute':'执行退款','reconciliation-start':'启动账单核对','issue-resolve':'记录差异处理','settlement-create':'生成店铺结算','settlement-decision':'复核结算单','settlement-payout':'确认模拟出款','adjustment-create':'创建差异调整','adjustment-approve':'复核差异调整','product-moderate':'运营下架 / 精选','review-visibility':'审核评价可见性','user-create':'创建平台用户','user-edit':'编辑用户与角色','role-create':'创建自定义角色','role-edit':'编辑角色授权','shop-create':'创建店铺','shop-edit':'编辑店铺','member-save':'维护店铺成员',
}

const detailEntries = computed(() => detail.value ? flattenEntries(detail.value).slice(0, 45) : [])
function flattenEntries(value: unknown, prefix = '', depth = 0): Array<{key:string;value:unknown}> {
  if (!record(value) || depth > 2) return prefix ? [{ key: prefix, value }] : []
  return Object.entries(value).flatMap(([key, item]) => {
    const label = prefix ? `${prefix}.${key}` : key
    if (record(item)) return flattenEntries(item, label, depth + 1)
    return [{ key: label, value: item }]
  })
}
function labelFor(key: string) {
  const short = key.split('.').at(-1) ?? key
  const labels: Record<string,string> = {id:'资源 ID',orderId:'订单 ID',orderNo:'订单号',shopId:'店铺 ID',shopName:'店铺',batchNo:'批次号',settlementNo:'结算单号',afterSaleNo:'售后单号',refundNo:'退款单号',paymentNo:'支付单号',title:'书名',isbn:'ISBN',status:'状态',financialStatus:'财务状态',salePrice:'售价',payableAmount:'应付',requestedAmount:'申请金额',amount:'金额',unitPrice:'单价',lineAmount:'行金额',version:'版本',reason:'原因',reviewNote:'审核说明',description:'说明',eventTime:'轨迹时间',createdAt:'创建时间',traceId:'跟踪号',source:'来源',evidenceFileIds:'售后凭证'}
  return labels[short] ?? short
}

function actionForRow(row: ApiRow): Array<{label:string;action:ActionName|'details'|'members';primary?:boolean}> {
  const status = String(row.status ?? '')
  switch (route.path) {
    case '/workbench/catalog/books': return [{label:'编辑',action:'book-edit'}]
    case '/workbench/catalog/categories': return [{label:'编辑',action:'category-edit'}]
    case '/workbench/merchant/products': return [{label:'编辑',action:'product-edit'},{label:status === 'ON_SALE' ? '商家下架' : '上架',action:'product-status'}]
    case '/workbench/merchant/inventory': return [{label:'调整实物量',action:'inventory-adjust'}]
    case '/workbench/merchant/orders': return [{label:'订单详情',action:'details'},...(status === 'PAID' ? [{label:'整体发货',action:'order-ship' as const,primary:true}] : []),...(row.shipment && record(row.shipment) ? [{label:'录入轨迹',action:'shipment-event' as const}] : [])]
    case '/workbench/support/after-sales': return [{label:'查看与凭证',action:'details'},...(status === 'REQUESTED' ? [{label:'审核',action:'sale-decision' as const,primary:true}] : [])]
    case '/workbench/warehouse/returns': return [{label:'验收记录',action:'details'},...(status === 'RETURNING' ? [{label:'验收退货',action:'return-inspection' as const,primary:true}] : [])]
    case '/workbench/finance/refunds': return [{label:'退款详情',action:'details'},...(['CREATED','FAILED'].includes(status) ? [{label:'执行 / 重试',action:'refund-execute' as const,primary:true}] : [])]
    case '/workbench/finance/reconciliation': return issueTable.value
      ? [{label:'差异详情',action:'details'},...(status === 'OPEN' ? [{label:'记录差异处理',action:'issue-resolve' as const,primary:true}] : [])]
      : [{label:'核对详情',action:'details'}]
    case '/workbench/finance/adjustments': return [{label:'调整详情',action:'details'},...(status === 'PENDING' ? [{label:'独立复核',action:'adjustment-approve' as const,primary:true}] : [])]
    case '/workbench/finance/settlements': return [{label:'结算明细',action:'details'},...(status === 'DRAFT' ? [{label:'审核',action:'settlement-decision' as const}] : []),...(status === 'APPROVED' && Number(String(row.payableAmount)) > 0 ? [{label:'模拟出款',action:'settlement-payout' as const,primary:true}] : [])]
    case '/workbench/merchant/settlements': return [{label:'只读明细',action:'details'}]
    case '/workbench/ops/moderation': return moderationReviews.value ? [{label:String(row.status)==='VISIBLE'?'隐藏评价':'调整可见性',action:'review-visibility'}] : [{label:'下架 / 精选',action:'product-moderate'}]
    case '/workbench/admin/users': return memberTable.value ? [{label:'编辑角色',action:'role-edit'}] : [{label:'编辑账号',action:'user-edit'}]
    case '/workbench/admin/shops': return [{label:'编辑店铺',action:'shop-edit'},{label:'管理成员',action:'members'}]
    case '/workbench/admin/audit': return [{label:'查看脱敏摘要',action:'details'}]
    default: return []
  }
}

async function loadReference(action: ActionName) {
  if (['book-create','book-edit','product-create','category-create','category-edit'].includes(action) && categories.value.length === 0) categories.value = await callOperation<CategoryView[]>('API-92')
  if (['product-create'].includes(action) && books.value.length === 0) {
    const result = await callOperation<Page<ApiRow>>('API-63', { query: { page: 1, pageSize: 100 } })
    books.value = result.items
  }
  if (['user-create','user-edit','role-create','role-edit'].includes(action) && roles.value.length === 0) {
    const result = await callOperation<Page<ApiRow>>('API-73', { query: { page: 1, pageSize: 100 } })
    roles.value = result.items
  }
  if (['role-create','role-edit'].includes(action) && permissions.value.length === 0) permissions.value = await callOperation<ApiRow[]>('API-76')
  if (action === 'member-save') {
    if (users.value.length === 0) users.value = (await callOperation<Page<ApiRow>>('API-70', { query: { page: 1, pageSize: 100 } })).items
  }
  if (action === 'adjustment-create') {
    const [settlementPage, issuePage] = await Promise.all([
      callOperation<Page<ApiRow>>('API-58', { query: { page: 1, pageSize: 100 } }),
      callOperation<Page<ApiRow>>('API-56', { query: { status: 'OPEN', page: 1, pageSize: 100 } }),
    ])
    settlements.value = settlementPage.items
    issues.value = issuePage.items
  }
}

function defaultsFor(action: ActionName, row: ApiRow | null): Record<string, FieldValue> {
  const get = (key: string, fallback: FieldValue = '') => readField(row, key) as FieldValue ?? fallback
  switch (action) {
    case 'book-create': return { isbn:'',title:'',author:'',publisher:'',publicationDate:'',categoryId:'',description:'',status:'ACTIVE' }
    case 'book-edit': return { isbn:get('isbn'),title:get('title'),author:get('author'),publisher:get('publisher'),publicationDate:get('publicationDate',''),categoryId:get('categoryId'),description:get('description'),status:get('status') }
    case 'category-create': return { name:'',parentId:'',sortOrder:0,status:'ACTIVE' }
    case 'category-edit': return { name:get('name'),parentId:get('parentId',''),sortOrder:get('sortOrder',0),status:get('status') }
    case 'product-create': return { bookId:'',skuCode:'',salePrice:'',description:'' }
    case 'product-edit': return { salePrice:get('salePrice'),description:get('description') }
    case 'product-status': return { status:get('status') === 'ON_SALE' ? 'OFF_SHELF' : 'ON_SALE' }
    case 'inventory-adjust': return { delta:0,reason:'' }
    case 'order-ship': return { carrier:'',trackingNo:'' }
    case 'shipment-event': return { eventTime:new Date().toISOString().slice(0,16),description:'' }
    case 'sale-decision': return { approved:'true',note:'' }
    case 'return-inspection': return { accepted:'true',restock:false,note:'' }
    case 'reconciliation-start': return { billDate:plusDaysInShanghai(1),provider:'LOCAL_MOCK' }
    case 'issue-resolve': return { resolution:'',evidenceReference:'' }
    case 'settlement-create': return { shopId:'',periodDate:plusDaysInShanghai(1) }
    case 'settlement-decision': return { approved:'true',note:'' }
    case 'adjustment-create': return { originalSettlementId:'',issueId:'',orderId:'',amount:'0.00',reason:'' }
    case 'adjustment-approve': return { note:'' }
    case 'product-moderate': return { featured:Boolean(get('featured',false)),reason:'' }
    case 'review-visibility': return { status:get('status') === 'VISIBLE' ? 'HIDDEN' : 'VISIBLE',reason:'' }
    case 'user-create': return { username:'',nickname:'',initialPassword:'',roleIds:[] }
    case 'user-edit': return { nickname:get('nickname'),status:get('status'),roleIds:roles.value.filter(role => (row?.roles as string[] | undefined)?.includes(String(role.code))).map(role => String(role.id)) }
    case 'role-create': return { code:'',name:'',permissionIds:[] }
    case 'role-edit': return { name:get('name'),status:get('status'),permissionIds:(row?.permissionIds as string[] | undefined) ?? [] }
    case 'shop-create': return { name:'',contactName:'',contactPhone:'',status:'ACTIVE' }
    case 'shop-edit': return { name:get('name'),contactName:get('contactName'),contactPhone:get('contactPhone'),status:get('status') }
    case 'member-save': return { userId:'',duty:'MERCHANT',status:'ACTIVE' }
  }
  throw new Error(`没有为操作 ${action} 配置表单默认值`)
}

async function prepareRowAction(action: ActionName, row: ApiRow) {
  let target = row
  if (action === 'order-ship' && workbench.selectedShopId) target = await callOperation<ApiRow>('API-39', { path: { shopId: workbench.selectedShopId, id: idOf(row) }, shopScoped: true })
  return target
}

async function openAction(action: ActionName, row: ApiRow | null = null) {
  actionError.value = null
  selectedFile.value = null
  actionRow.value = row
  try {
    await loadReference(action)
    if (row) actionRow.value = await prepareRowAction(action, row)
    if (action === 'shipment-event' && row?.shipment && record(row.shipment)) actionRow.value = row
    formValues.value = defaultsFor(action, actionRow.value)
    activeAction.value = action
    actionOpen.value = true
  } catch (cause) { actionError.value = cause }
}

function formString(name: string) { const value = formValues.value[name]; return typeof value === 'string' ? value : String(value ?? '') }
function setFormString(name: string, value: string | number) { formValues.value[name] = value }
function formBool(name: string) { return formValues.value[name] === true || formValues.value[name] === 'true' }
function formNumber(name: string) { const value = formValues.value[name]; return typeof value === 'number' ? value : Number(value) }
function formIds(name: string) { const value = formValues.value[name]; return Array.isArray(value) ? value : [] }
function safeDateTimeLocal(value: string) { const parsed = new Date(value); return Number.isNaN(parsed.valueOf()) ? value : parsed.toISOString() }

async function submitAction() {
  if (!activeAction.value) return
  actionBusy.value = true
  actionError.value = null
  try {
    await persistAction(activeAction.value)
    actionOpen.value = false
    statusMessage.value = '操作已由服务端受理；页面正在重新读取持久状态。'
    await load()
  } catch (cause) { actionError.value = cause }
  finally { actionBusy.value = false }
}

async function persistAction(action: ActionName) {
  const row = actionRow.value
  const id = row ? idOf(row) : ''
  const shopId = workbench.selectedShopId ?? ''
  const userScope = session.user?.id ?? 'operator'
  let operation: ApiOperationId
  let path: Record<string,string> | undefined
  let body: Record<string,unknown> | undefined
  let scope: string | undefined
  let shopScoped = false
  switch (action) {
    case 'book-create': case 'book-edit': {
      operation = action === 'book-create' ? 'API-64' : 'API-65'
      if (action === 'book-edit') path = { id }
      if (selectedFile.value) {
        const fileForm = new FormData(); fileForm.append('file',selectedFile.value); fileForm.append('purpose','BOOK_COVER')
        const file = await callOperation<{id:string}>('API-21',{body:fileForm})
        formValues.value.coverFileId = file.id
      }
      body = { isbn:formString('isbn').trim(),title:formString('title').trim(),author:formString('author').trim(),publisher:formString('publisher').trim(),publicationDate:formString('publicationDate') || null,categoryId:formString('categoryId'),description:formString('description'),status:formString('status'),...(formValues.value.coverFileId ? {coverFileId:formString('coverFileId')} : {}) }
      break
    }
    case 'category-create': case 'category-edit': operation = action === 'category-create' ? 'API-66' : 'API-67'; if (action === 'category-edit') path={id}; body={name:formString('name').trim(),parentId:formString('parentId') || null,sortOrder:formNumber('sortOrder'),status:formString('status')}; break
    case 'product-create': operation='API-16'; path={shopId}; shopScoped=true; body={bookId:formString('bookId'),description:formString('description'),skuCode:formString('skuCode').trim(),salePrice:formString('salePrice').trim()}; break
    case 'product-edit': operation='API-17'; path={shopId,id}; shopScoped=true; body={description:formString('description'),salePrice:formString('salePrice').trim(),version:Number(row?.version)}; break
    case 'product-status': operation='API-18'; path={shopId,id}; shopScoped=true; body={status:formString('status'),version:Number(row?.version)}; break
    case 'inventory-adjust': operation='API-20'; path={shopId,skuId:String(row?.skuId ?? '')}; shopScoped=true; body={delta:formNumber('delta'),reason:formString('reason').trim(),version:Number(row?.version)}; scope=`${userScope}:sku:${String(row?.skuId)}`; break
    case 'order-ship': operation='API-40'; path={shopId,id}; shopScoped=true; body={carrier:formString('carrier').trim(),trackingNo:formString('trackingNo').trim(),version:Number(row?.version)}; scope=`${userScope}:shipment:${id}`; break
    case 'shipment-event': {
      const shipment = record(row?.shipment) ? row.shipment : row
      const shipmentId = String(shipment?.id ?? '')
      operation='API-41'; path={shopId,id:shipmentId}; shopScoped=true; body={eventTime:safeDateTimeLocal(formString('eventTime')),description:formString('description').trim()}; break
    }
    case 'sale-decision': operation='API-49'; path={id}; body={approved:formBool('approved'),note:formString('note').trim()}; break
    case 'return-inspection': operation='API-51'; path={shopId,id}; shopScoped=true; body={accepted:formBool('accepted'),restock:formBool('accepted') && formBool('restock'),note:formString('note').trim()}; scope=`${userScope}:return:${id}`; break
    case 'refund-execute': operation='API-53'; path={id}; scope=`${userScope}:refund:${id}`; break
    case 'reconciliation-start': operation='API-55'; body={billDate:formString('billDate'),provider:formString('provider')}; scope=`${userScope}:reconciliation:${formString('provider')}:${formString('billDate')}`; break
    case 'issue-resolve': operation='API-57'; path={id}; body={resolution:formString('resolution').trim(),evidenceReference:formString('evidenceReference').trim()}; break
    case 'settlement-create': operation='API-59'; body={shopId:formString('shopId').trim(),periodDate:formString('periodDate')}; scope=`${userScope}:settlement:${formString('shopId')}:${formString('periodDate')}`; break
    case 'settlement-decision': operation='API-60'; path={id}; body={approved:formBool('approved'),note:formString('note').trim(),version:Number(row?.version)}; scope=`${userScope}:settlement:${id}:decision`; break
    case 'settlement-payout': operation='API-61'; path={id}; body={version:Number(row?.version)}; scope=`${userScope}:settlement:${id}:payout`; break
    case 'adjustment-create': operation='API-81'; body={originalSettlementId:formString('originalSettlementId'),orderId:formString('orderId').trim(),issueId:formString('issueId'),amount:formString('amount').trim(),reason:formString('reason').trim()}; break
    case 'adjustment-approve': operation='API-83'; path={id}; body={note:formString('note').trim()}; break
    case 'product-moderate': operation='API-68'; path={id}; body={offShelf:true,featured:formBool('featured'),reason:formString('reason').trim(),version:Number(row?.version)}; break
    case 'review-visibility': operation='API-69'; path={id}; body={status:formString('status'),reason:formString('reason').trim()}; break
    case 'user-create': operation='API-71'; body={username:formString('username').trim(),nickname:formString('nickname').trim(),initialPassword:formString('initialPassword'),roleIds:formIds('roleIds')}; break
    case 'user-edit': operation='API-72'; path={id}; body={nickname:formString('nickname').trim(),status:formString('status'),roleIds:formIds('roleIds')}; break
    case 'role-create': operation='API-74'; body={code:formString('code').trim(),name:formString('name').trim(),permissionIds:formIds('permissionIds')}; break
    case 'role-edit': operation='API-75'; path={id}; body={name:formString('name').trim(),status:formString('status'),permissionIds:formIds('permissionIds')}; break
    case 'shop-create': case 'shop-edit': operation=action === 'shop-create' ? 'API-85' : 'API-86'; if(action === 'shop-edit') path={id}; body={name:formString('name').trim(),contactName:formString('contactName').trim(),contactPhone:formString('contactPhone').trim(),status:formString('status')}; break
    case 'member-save': operation='API-88'; path={shopId:String(memberShop.value?.id ?? ''),userId:formString('userId'),duty:formString('duty')}; body={status:formString('status')}; break
  }
  await callOperation(operation, { path, body, shopScoped, idempotencyScope: scope })
}

async function openDetails(row: ApiRow) {
  detailBusy.value = true
  detailOpen.value = true
  detailTitle.value = String(row.orderNo ?? row.afterSaleNo ?? row.settlementNo ?? row.refundNo ?? row.id ?? pageLabel.value)
  detail.value = row
  detailEvidence.value = []
  try {
    if (route.path === '/workbench/merchant/orders' && workbench.selectedShopId) {
      detail.value = await callOperation<ApiRow>('API-39', { path:{shopId:workbench.selectedShopId,id:idOf(row)},shopScoped:true })
    } else if (route.path === '/workbench/support/after-sales') {
      const [sale, orderView] = await Promise.all([
        callOperation<ApiRow>('API-48',{path:{id:idOf(row)}}),callOperation<ApiRow>('API-78',{path:{id:idOf(row)}}),
      ])
      detail.value = { ...sale, relatedOrder:orderView }
      if (Array.isArray(sale.evidenceFileIds)) detailEvidence.value = sale.evidenceFileIds.map(String)
    } else if (route.path === '/workbench/finance/settlements') detail.value = await callOperation<ApiRow>('API-79',{path:{id:idOf(row)}})
    else if (route.path === '/workbench/merchant/settlements' && workbench.selectedShopId) detail.value = await callOperation<ApiRow>('API-80',{path:{shopId:workbench.selectedShopId,id:idOf(row)},shopScoped:true})
    else if (route.path === '/workbench/warehouse/returns' && workbench.selectedShopId) {
      const pageResult = await callOperation<Page<ApiRow>>('API-50',{path:{shopId:workbench.selectedShopId},query:{page:1,pageSize:100},shopScoped:true})
      detail.value = pageResult.items.find(item => idOf(item) === idOf(row)) ?? row
      const evidence = readField(detail.value,'evidenceFileIds')
      if (Array.isArray(evidence)) detailEvidence.value=evidence.map(String)
    }
  } catch (cause) { actionError.value = cause }
  finally { detailBusy.value = false }
}

async function openMembers(shop: ApiRow) {
  memberShop.value = shop
  memberOpen.value = true
  memberBusy.value = true
  try { members.value = (await callOperation<Page<ApiRow>>('API-87',{path:{shopId:idOf(shop)},query:{page:1,pageSize:100}})).items }
  catch (cause) { actionError.value = cause }
  finally { memberBusy.value = false }
}
function changeMembership(member: ApiRow) {
  void openAction('member-save',{...member,shopId:memberShop.value?.id})
}

function closeActionDialog() {
  formValues.value = {}
  selectedFile.value = null
  activeAction.value = null
  actionRow.value = null
}

async function previewPrivateEvidence(fileId: string) {
  evidenceLoading.value = true
  try {
    const blob = await downloadFile(fileId)
    if (evidenceUrl) URL.revokeObjectURL(evidenceUrl)
    evidenceUrl = URL.createObjectURL(blob)
    evidencePreview.value = evidenceUrl
  } catch (cause) { actionError.value = cause }
  finally { evidenceLoading.value = false }
}
function closeDetail() {
  detailOpen.value = false
  evidencePreview.value = ''
  if (evidenceUrl) URL.revokeObjectURL(evidenceUrl)
  evidenceUrl = null
}
function onFileSelected(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0] ?? null
  if (!file) { selectedFile.value = null; return }
  if (!['image/jpeg','image/png','image/webp'].includes(file.type) || file.size > 5 * 1024 * 1024) {
    actionError.value = new Error('请选择 5 MB 以内的 JPEG、PNG 或 WebP 图片。')
    ;(event.target as HTMLInputElement).value = ''
    selectedFile.value = null
    return
  }
  selectedFile.value = file
}

function displayAction(action: ActionName|'details'|'members', row: ApiRow) {
  if (action === 'details') void openDetails(row)
  else if (action === 'members') void openMembers(row)
  else void openAction(action, row)
}
function createActionForPage(): ActionName | null {
  if (route.path === '/workbench/catalog/books') return 'book-create'
  if (route.path === '/workbench/catalog/categories') return 'category-create'
  if (route.path === '/workbench/merchant/products') return 'product-create'
  if (route.path === '/workbench/finance/reconciliation' && !issueTable.value) return 'reconciliation-start'
  if (route.path === '/workbench/finance/settlements') return 'settlement-create'
  if (route.path === '/workbench/finance/adjustments') return 'adjustment-create'
  if (route.path === '/workbench/admin/users') return memberTable.value ? 'role-create' : 'user-create'
  if (route.path === '/workbench/admin/shops') return 'shop-create'
  return null
}
const createLabel = computed(() => createActionForPage() ? ({'book-create':'新增书目','category-create':'新增分类','product-create':'创建本店商品','reconciliation-start':'发起账单核对','settlement-create':'生成结算候选','adjustment-create':'创建差异调整','user-create':'创建用户','role-create':'创建角色','shop-create':'创建店铺'} as Record<string,string>)[createActionForPage() as string] : '')
function addResource() { const action = createActionForPage(); if (action) void openAction(action) }

function changeSubtab(next: string) { subtab.value = next; page.value=1; filterStatus.value=''; void load() }
function fieldOptions(field: FieldSpec) { return field.options ?? [] }
function fileChange(event: Event) { onFileSelected(event) }
function actionLead() {
  const row = actionRow.value
  if (!row || !activeAction.value) return ''
  if (activeAction.value === 'settlement-payout') return `结算单 ${String(row.settlementNo)}，应付 ¥${String(row.payableAmount)}。此操作只记录模拟出款凭证，不会转账。`
  if (activeAction.value === 'refund-execute') return `退款单 ${String(row.refundNo)}，金额 ¥${String(row.amount)}。处理中不重复执行；只有服务端明确失败后才可用新幂等键重试。`
  if (activeAction.value === 'settlement-decision') return `结算单 ${String(row.settlementNo)}，创建人 ${String(row.createdBy)}。后端会强制创建人与审核人分离。`
  if (activeAction.value === 'product-status' && formString('status') === 'ON_SALE') return '商品上架由商家决定；运营不能强制商家下架商品重新上架。'
  return ''
}

onMounted(() => { if (route.path === '/workbench/admin/users') subtab.value='users'; if (route.path === '/workbench/ops/moderation') subtab.value='products'; if (route.path === '/workbench/finance/reconciliation') subtab.value='runs'; void load() })
watch(() => route.fullPath, () => { page.value=1; search.value=''; filterStatus.value=''; if (route.path === '/workbench/admin/users') subtab.value='users'; if (route.path === '/workbench/ops/moderation') subtab.value='products'; if (route.path === '/workbench/finance/reconciliation') subtab.value='runs'; void load() })
watch(() => workbench.selectedShopId, () => { if (isShopPage.value) void load() })
watch(() => formValues.value.accepted, accepted => { if (accepted === 'false' || accepted === false) formValues.value.restock=false })
onBeforeUnmount(() => { activeController?.abort(); if (evidenceUrl) URL.revokeObjectURL(evidenceUrl) })
</script>

<template>
  <section class="workbench-module">
    <div class="module-heading"><div><p class="eyebrow">工作台 / {{ pageLabel }}</p><h2>{{ pageLabel }}</h2><p v-if="isShopPage && workbench.selectedShopId" class="muted">本店数据 · {{ session.user?.shops.find(shop => shop.id === workbench.selectedShopId)?.name }}</p></div>
      <button v-if="createLabel" class="button" type="button" @click="addResource">{{ createLabel }}</button>
    </div>

    <nav v-if="route.path === '/workbench/admin/users'" class="module-tabs" aria-label="用户和角色视图"><button :aria-pressed="subtab==='users'" @click="changeSubtab('users')">用户与角色分配</button><button :aria-pressed="subtab==='roles'" @click="changeSubtab('roles')">角色与动作权限</button></nav>
    <nav v-if="route.path === '/workbench/ops/moderation'" class="module-tabs" aria-label="运营审核类型"><button :aria-pressed="subtab==='products'" @click="changeSubtab('products')">商品</button><button :aria-pressed="subtab==='reviews'" @click="changeSubtab('reviews')">评价</button></nav>
    <nav v-if="route.path === '/workbench/finance/reconciliation'" class="module-tabs" aria-label="对账视图"><button :aria-pressed="subtab==='runs'" @click="changeSubtab('runs')">核对批次</button><button :aria-pressed="subtab==='issues'" @click="changeSubtab('issues')">差异处理</button></nav>

    <form class="workbench-filters" @submit.prevent="applyFilters">
      <label v-if="['/workbench/catalog/books','/workbench/merchant/products','/workbench/merchant/orders','/workbench/ops/moderation','/workbench/admin/users'].includes(route.path)">搜索
        <input v-model="search" :placeholder="route.path.includes('orders') ? '订单号' : route.path.includes('users') ? '用户名或昵称' : route.path.includes('moderation') && moderationReviews ? '商品 ID' : '书名、ISBN 或关键词'" />
      </label>
      <label v-if="filterStatuses.length">状态
        <select v-model="filterStatus"><option value="">全部状态</option><option v-for="option in filterStatuses" :key="option[0]" :value="option[0]">{{ option[1] }}</option></select>
      </label>
      <label v-if="['/workbench/finance/refunds','/workbench/finance/reconciliation','/workbench/finance/settlements','/workbench/finance/adjustments'].includes(route.path)">店铺 ID（可选）<input v-model="shopFilter" inputmode="numeric" /></label>
      <template v-if="route.path === '/workbench/admin/audit'">
        <label>操作者 ID<input v-model="actorFilter" inputmode="numeric" /></label><label>操作代码<input v-model="actionFilter" maxlength="80" /></label><label>跟踪号<input v-model="traceFilter" maxlength="64" /></label>
        <label>开始时间<input v-model="fromFilter" type="datetime-local" /></label><label>结束时间<input v-model="toFilter" type="datetime-local" /></label>
      </template>
      <button class="button button-secondary" type="submit">查询</button><button class="text-button" type="button" @click="clearFilters">重置</button>
    </form>
    <p v-if="statusMessage" class="inline-message" role="status">{{ statusMessage }}</p>
    <p v-if="actionError && !actionOpen && !detailOpen" class="inline-error" role="alert">{{ actionError instanceof Error ? actionError.message : '操作失败，请重试。' }}</p>

    <AsyncState :loading="loading" :error="error" :empty="rows.length === 0" empty-text="当前筛选没有记录。不会填充演示数据。" empty-action-text="重新读取服务端" @retry="load">
      <div class="table-summary">共 {{ total }} 条服务端记录</div>
      <div class="responsive-table" role="region" :aria-label="`${pageLabel}数据表格`" tabindex="0">
        <table>
          <thead><tr><th v-for="column in columns" :key="column.key" :class="{ 'money-cell': column.money }">{{ column.label }}</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="row in rows" :key="idOf(row) || JSON.stringify(row)">
              <td v-for="column in columns" :key="column.key" :class="[{ 'money-cell': column.money }, `cell-${column.key.split('.').at(-1)}`]">
                <MoneyText v-if="column.money && typeof readField(row,column.key) === 'string'" :value="String(readField(row,column.key))" />
                <span v-else>{{ visibleText(readField(row,column.key)) }}</span>
              </td>
              <td class="row-actions"><button v-for="item in actionForRow(row)" :key="item.action" class="text-button" :class="{ 'primary-action': item.primary }" type="button" @click="displayAction(item.action,row)">{{ item.label }}</button></td>
            </tr>
          </tbody>
        </table>
      </div>
      <nav v-if="total > pageSize" class="pagination" aria-label="后台列表分页"><button class="button button-secondary" :disabled="page <= 1" @click="changePage(page-1)">上一页</button><span>第 {{ page }} 页 · {{ total }} 条</span><button class="button button-secondary" :disabled="page * pageSize >= total" @click="changePage(page+1)">下一页</button></nav>
    </AsyncState>

    <ElDialog v-model="actionOpen" :title="activeAction ? actionTitles[activeAction] : '操作记录'" width="min(680px, 94vw)" destroy-on-close @closed="closeActionDialog">
      <p v-if="actionLead()" class="dialog-context">{{ actionLead() }}</p>
      <p v-if="activeAction === 'product-status' && formString('status') === 'ON_SALE'" class="inline-error">运营不能用此操作强制上架；仅商家可上架本店商品。</p>
      <form class="dialog-form" @submit.prevent="submitAction">
        <label v-for="field in fields" :key="field.name" class="form-field" :class="{ 'checkbox-field': field.type === 'checkbox' }">
          <span v-if="field.type !== 'checkbox'">{{ field.label }}<b v-if="field.required"> *</b></span>
          <textarea v-if="field.type === 'textarea'" :value="formString(field.name)" @input="setFormString(field.name, ($event.target as HTMLTextAreaElement).value)" :maxlength="field.maxLength" :required="field.required" rows="4"></textarea>
          <select v-else-if="field.type === 'select'" v-model="formValues[field.name]" :multiple="field.multiple" :required="field.required" :size="field.multiple ? 5 : undefined">
            <option v-if="!field.multiple && !field.required" value="">请选择</option><option v-for="option in fieldOptions(field)" :key="option.value" :value="option.value">{{ option.label }}</option>
          </select>
          <input v-else-if="field.type === 'checkbox'" v-model="formValues[field.name]" type="checkbox" />
          <input v-else v-model="formValues[field.name]" :type="field.type ?? 'text'" :maxlength="field.maxLength" :required="field.required" :step="field.type === 'number' ? '1' : undefined" :inputmode="field.name.toLowerCase().includes('id') ? 'numeric' : undefined" />
          <small v-if="field.hint">{{ field.hint }}</small>
        </label>
        <label v-if="activeAction === 'book-create' || activeAction === 'book-edit'" class="form-field">封面图片（可选）<input type="file" accept="image/jpeg,image/png,image/webp" @change="fileChange" /><small>仅上传 5 MB 以内 JPEG、PNG 或 WebP；文件由 API-21 验证并重新编码。</small></label>
        <p v-if="actionRow && API_VERSION_ACTIONS.has(activeAction ?? '')" class="muted">提交将携带当前记录版本 {{ actionRow.version }}，发生冲突时请重新读取后确认。</p>
        <p v-if="actionError" class="inline-error" role="alert">{{ actionError instanceof ApiError ? `${actionError.message}（${actionError.result.traceId}）` : actionError instanceof Error ? actionError.message : '操作失败。' }}</p>
        <div class="dialog-actions"><button class="button button-secondary" type="button" @click="actionOpen=false">取消</button><button class="button" type="submit" :disabled="actionBusy">{{ actionBusy ? '正在提交…' : activeAction === 'settlement-payout' ? '确认模拟出款' : '提交到服务端' }}</button></div>
      </form>
    </ElDialog>

    <ElDialog v-model="detailOpen" :title="detailTitle" width="min(850px, 96vw)" @closed="closeDetail">
      <AsyncState :loading="detailBusy" :error="null" :empty="!detail" empty-text="没有详情记录。">
        <dl class="detail-grid"><div v-for="entry in detailEntries" :key="entry.key"><dt>{{ labelFor(entry.key) }}</dt><dd>{{ visibleText(entry.value) }}</dd></div></dl>
        <section v-if="route.path === '/workbench/merchant/orders' && detail && record(detail.shipment)" class="evidence-list"><h3>物流轨迹</h3><button class="button button-secondary" type="button" @click="openAction('shipment-event',detail)">人工录入物流轨迹</button></section>
        <section v-if="detailEvidence.length" class="evidence-list"><h3>受权售后凭证</h3><p class="muted">通过 API-22 鉴权读取；凭证不会公开成静态链接。</p><button v-for="fileId in detailEvidence" :key="fileId" class="button button-secondary" type="button" @click="previewPrivateEvidence(fileId)">{{ evidenceLoading ? '正在读取…' : `查看凭证 ${fileId}` }}</button></section>
        <img v-if="evidencePreview" class="evidence-preview" :src="evidencePreview" alt="售后凭证图片预览" />
      </AsyncState>
    </ElDialog>

    <ElDialog v-model="memberOpen" :title="`${String(memberShop?.name ?? '店铺')} · 成员职责`" width="min(720px, 96vw)">
      <AsyncState :loading="memberBusy" :error="null" :empty="members.length === 0" empty-text="当前店铺没有成员记录。" empty-action-text="刷新成员" @retry="memberShop && openMembers(memberShop)">
        <div class="responsive-table"><table><thead><tr><th>用户</th><th>职责</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="member in members" :key="idOf(member)"><td>{{ member.username }} · {{ member.userId }}</td><td>{{ visibleText(member.duty) }}</td><td>{{ visibleText(member.status) }}</td><td><button class="text-button" @click="changeMembership(member)">编辑成员状态</button></td></tr></tbody></table></div>
      </AsyncState>
      <button class="button button-secondary" type="button" @click="openAction('member-save')">添加或启用成员</button>
    </ElDialog>
  </section>
</template>

