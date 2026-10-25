import { createRouter, createWebHistory } from 'vue-router'
import { useSessionStore } from './stores/session'

const StorefrontPage = () => import('./pages/StorefrontPage.vue')
const WorkbenchPage = () => import('./pages/WorkbenchPage.vue')
const workbenchRoute = (path: string, title: string, permission = '') => ({
  path, component: WorkbenchPage,
  meta: { requiresAuth: true, workbench: true, title, permission },
})

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'home', component: StorefrontPage, meta: { page: 'home' } },
    { path: '/search', name: 'search', component: StorefrontPage, meta: { page: 'search' } },
    { path: '/products/:id', name: 'product', component: StorefrontPage, meta: { page: 'product' } },
    { path: '/login', name: 'login', component: StorefrontPage, meta: { page: 'login', guestOnly: true } },
    { path: '/register', name: 'register', component: StorefrontPage, meta: { page: 'register', guestOnly: true } },
    { path: '/cart', name: 'cart', component: StorefrontPage, meta: { page: 'cart', requiresAuth: true } },
    { path: '/checkout', name: 'checkout', component: StorefrontPage, meta: { page: 'checkout', requiresAuth: true } },
    { path: '/checkout/batches/:id', name: 'batch', component: StorefrontPage, meta: { page: 'batch', requiresAuth: true } },
    { path: '/payments/:id', name: 'payment', component: StorefrontPage, meta: { page: 'payment', requiresAuth: true } },
    { path: '/account/orders', name: 'orders', component: StorefrontPage, meta: { page: 'orders', requiresAuth: true } },
    { path: '/account/orders/:id', name: 'order', component: StorefrontPage, meta: { page: 'order', requiresAuth: true } },
    { path: '/account/addresses', name: 'addresses', component: StorefrontPage, meta: { page: 'addresses', requiresAuth: true } },
    { path: '/account/after-sales/new', name: 'after-sale-new', component: StorefrontPage, meta: { page: 'after-sale-new', requiresAuth: true } },
    { path: '/account/after-sales', name: 'after-sales', component: StorefrontPage, meta: { page: 'after-sales', requiresAuth: true } },
    { path: '/account/after-sales/:id', name: 'after-sale', component: StorefrontPage, meta: { page: 'after-sale', requiresAuth: true } },
    { path: '/account/reviews/new', name: 'review-new', component: StorefrontPage, meta: { page: 'review-new', requiresAuth: true } },
    { path: '/account/security', name: 'security', component: StorefrontPage, meta: { page: 'security', requiresAuth: true } },

    workbenchRoute('/workbench', '工作台首页', 'dashboard.read'),
    workbenchRoute('/workbench/catalog/books', '公共书目', 'catalog.book.write'),
    workbenchRoute('/workbench/catalog/categories', '分类管理', 'catalog.category.manage'),
    workbenchRoute('/workbench/merchant/products', '本店商品', 'shop.product.read'),
    workbenchRoute('/workbench/merchant/inventory', '库存调整', 'shop.inventory.read'),
    workbenchRoute('/workbench/merchant/orders', '订单发货', 'shop.order.read'),
    workbenchRoute('/workbench/support/after-sales', '售后审核', 'support.after_sale.read'),
    workbenchRoute('/workbench/warehouse/returns', '退货验收', 'shop.return.inspect'),
    workbenchRoute('/workbench/finance/refunds', '退款处理', 'finance.refund.read'),
    workbenchRoute('/workbench/finance/reconciliation', '渠道对账', 'finance.reconcile'),
    workbenchRoute('/workbench/finance/settlements', '平台结算', 'finance.settlement.read'),
    workbenchRoute('/workbench/finance/adjustments', '差异调整', 'finance.adjustment.manage'),
    workbenchRoute('/workbench/merchant/settlements', '本店账单', 'shop.settlement.read'),
    workbenchRoute('/workbench/ops/moderation', '内容审核', 'catalog.moderate'),
    workbenchRoute('/workbench/admin/users', '用户与角色', 'iam.user.manage'),
    workbenchRoute('/workbench/admin/shops', '店铺与成员', 'platform.shop.manage'),
    workbenchRoute('/workbench/admin/audit', '审计记录', 'platform.audit.read'),
    workbenchRoute('/workbench/observability', '运行状态', 'platform.audit.read'),
    { path: '/forbidden', name: 'forbidden', component: () => import('./pages/AccessDeniedPage.vue'), meta: { title: '无权访问' } },
    { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('./pages/NotFoundPage.vue'), meta: { title: '页面不存在' } },
  ],
  scrollBehavior: () => ({ top: 0 }),
})

function safeInternalPath(value: unknown) {
  return typeof value === 'string' && value.startsWith('/') && !value.startsWith('//') && !value.includes('://') ? value : '/'
}

router.beforeEach(async to => {
  const session = useSessionStore()
  await session.initialize()
  if (to.meta.guestOnly && session.isSignedIn) return safeInternalPath(to.query.returnTo) || '/'
  if (to.meta.requiresAuth && !session.isSignedIn) {
    return { name: 'login', query: { returnTo: safeInternalPath(to.fullPath) } }
  }
  if (typeof to.meta.permission === 'string' && to.meta.permission && !session.hasPermission(to.meta.permission)) {
    return { name: 'forbidden' }
  }
  return true
})
