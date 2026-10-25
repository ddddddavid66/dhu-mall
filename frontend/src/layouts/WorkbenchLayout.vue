<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useSessionStore } from '../stores/session'
import { useWorkbenchStore } from '../stores/workbench'

const route = useRoute()
const session = useSessionStore()
const workbench = useWorkbenchStore()
const menuOpen = ref(false)
const groups = [
  { title: '总览', items: [{ label: '工作台首页', to: '/workbench', permission: 'dashboard.read' }] },
  { title: '商品与仓库', items: [
    { label: '公共书目', to: '/workbench/catalog/books', permission: 'catalog.book.write' },
    { label: '分类管理', to: '/workbench/catalog/categories', permission: 'catalog.category.manage' },
    { label: '本店商品', to: '/workbench/merchant/products', permission: 'shop.product.read' },
    { label: '库存调整', to: '/workbench/merchant/inventory', permission: 'shop.inventory.read' },
    { label: '订单发货', to: '/workbench/merchant/orders', permission: 'shop.order.read' },
    { label: '退货验收', to: '/workbench/warehouse/returns', permission: 'shop.return.inspect' },
  ] },
  { title: '客服与财务', items: [
    { label: '售后审核', to: '/workbench/support/after-sales', permission: 'support.after_sale.read' },
    { label: '退款处理', to: '/workbench/finance/refunds', permission: 'finance.refund.read' },
    { label: '渠道对账', to: '/workbench/finance/reconciliation', permission: 'finance.reconcile' },
    { label: '平台结算', to: '/workbench/finance/settlements', permission: 'finance.settlement.read' },
    { label: '差异调整', to: '/workbench/finance/adjustments', permission: 'finance.adjustment.manage' },
    { label: '本店账单', to: '/workbench/merchant/settlements', permission: 'shop.settlement.read' },
  ] },
  { title: '平台管理', items: [
    { label: '内容审核', to: '/workbench/ops/moderation', permission: 'catalog.moderate' },
    { label: '用户与角色', to: '/workbench/admin/users', permission: 'iam.user.manage' },
    { label: '店铺与成员', to: '/workbench/admin/shops', permission: 'platform.shop.manage' },
    { label: '审计记录', to: '/workbench/admin/audit', permission: 'platform.audit.read' },
    { label: '运行状态', to: '/workbench/observability', permission: 'platform.audit.read' },
  ] },
]
const visibleGroups = computed(() => groups.map(group => ({
  ...group,
  items: group.items.filter(item => !item.permission || session.hasPermission(item.permission)),
})).filter(group => group.items.length))
const currentLabel = computed(() => String(route.meta.title ?? '工作台'))

function selectShop(event: Event) {
  const id = (event.target as HTMLSelectElement).value
  const shop = session.user?.shops.find(item => item.id === id)
  if (shop) workbench.switchShop(shop.id, session.user)
}

workbench.syncShops(session.user)
</script>

<template>
  <div class="workbench-shell">
    <aside class="workbench-sidebar" :class="{ 'sidebar-open': menuOpen }">
      <RouterLink class="workbench-brand" to="/workbench">阅集 <span>管理工作台</span></RouterLink>
      <nav aria-label="管理工作台导航">
        <section v-for="group in visibleGroups" :key="group.title" class="nav-group">
          <h2>{{ group.title }}</h2>
          <RouterLink v-for="item in group.items" :key="item.to" :to="item.to" :aria-current="route.path === item.to ? 'page' : undefined" @click="menuOpen = false">
            {{ item.label }}
          </RouterLink>
        </section>
      </nav>
      <RouterLink class="back-to-store" to="/">← 返回商城</RouterLink>
    </aside>
    <section class="workbench-main">
      <header class="workbench-topbar">
        <button class="menu-toggle" type="button" aria-label="打开工作台导航" @click="menuOpen = !menuOpen">☰</button>
        <div><p class="eyebrow">{{ session.user?.roles.join(' · ') || '工作台' }}</p><h1>{{ currentLabel }}</h1></div>
        <label v-if="session.user?.shops.length" class="shop-context">
          <span>当前店铺</span>
          <select :value="workbench.selectedShopId ?? ''" @change="selectShop">
            <option v-for="shop in session.user.shops" :key="shop.id" :value="shop.id">{{ shop.name }}</option>
          </select>
        </label>
        <div class="topbar-user">{{ session.user?.nickname }}<RouterLink to="/account/security">账户</RouterLink></div>
      </header>
      <div class="workbench-body"><slot /></div>
    </section>
  </div>
</template>
