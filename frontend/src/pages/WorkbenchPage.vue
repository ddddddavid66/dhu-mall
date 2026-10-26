<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import AsyncState from '../components/AsyncState.vue'
import WorkbenchLayout from '../layouts/WorkbenchLayout.vue'
import { ApiError, callOperation } from '../api/client'
import type { ApiRow } from '../api/models'
import WorkbenchModulePage from './workbench/WorkbenchModulePage.vue'
import { useWorkbenchStore } from '../stores/workbench'
import { useSessionStore } from '../stores/session'

const route = useRoute()
const path = computed(() => route.path)
const isDashboard = computed(() => path.value === '/workbench')
const isObservability = computed(() => path.value === '/workbench/observability')
const buildMode = import.meta.env.MODE
const loading = ref(false)
const error = ref<unknown>(null)
const dashboard = ref<ApiRow | null>(null)
const workbench = useWorkbenchStore()
const session = useSessionStore()
let dashboardRequest = 0
const metricLabels: Record<string, string> = { todayOrderCount: '今日订单', todayPaidAmount: '今日实收（元）', pendingShipmentCount: '待发货订单', openAfterSaleCount: '进行中售后', unresolvedIssueCount: '待处理差异' }
const quickLinks = computed(() => [
  { path: '/workbench/merchant/orders', title: '订单履约', permission: 'shop.order.read' },
  { path: '/workbench/support/after-sales', title: '售后处理', permission: 'support.after_sale.read' },
  { path: '/workbench/finance/reconciliation', title: '渠道对账', permission: 'finance.reconcile' },
  { path: '/workbench/finance/settlements', title: '结算账单', permission: 'finance.settlement.read' },
  { path: '/workbench/admin/users', title: '用户管理', permission: 'iam.user.manage' },
  { path: '/workbench/admin/shops', title: '店铺管理', permission: 'platform.shop.manage' },
].filter(item => session.hasPermission(item.permission)))
const grafanaUrl = computed(() => {
  const candidate = (import.meta.env as ImportMetaEnv & { VITE_GRAFANA_URL?: string }).VITE_GRAFANA_URL
  if (!candidate) return ''
  try {
    const url = new URL(candidate)
    return url.protocol === 'https:' && !url.username && !url.password ? url.toString() : ''
  } catch { return '' }
})

async function loadDashboard() {
  const request = ++dashboardRequest
  loading.value = true
  error.value = null
  dashboard.value = null
  try {
    const value = await callOperation<ApiRow>('API-89', { query: { shopId: workbench.selectedShopId ?? undefined }, shopScoped: true })
    if (request === dashboardRequest) dashboard.value = value
  }
  catch (cause) { if (request === dashboardRequest) error.value = cause }
  finally { if (request === dashboardRequest) loading.value = false }
}

function traceIdOf(value: unknown) { return value instanceof ApiError ? value.result.traceId : '' }
onMounted(() => { if (isDashboard.value) void loadDashboard() })
watch(isDashboard, value => { if (value) void loadDashboard() })
watch(() => workbench.selectedShopId, () => { if (isDashboard.value) void loadDashboard() })
</script>

<template>
  <WorkbenchLayout>
    <section v-if="isDashboard" class="dashboard-page">
      <p class="eyebrow">阅集 · 管理工作台</p><h2>业务入口</h2>
      <p class="dashboard-intro">按北京时间统计当前授权范围，店铺切换后重新查询。</p>
      <AsyncState :loading="loading" :error="error" :empty="!dashboard || Object.keys(dashboard).length === 0" empty-text="当前角色没有可展示的业务指标，请使用下方授权管理入口。" empty-action-text="重新查询" @retry="loadDashboard">
        <div class="metric-grid"><article v-for="(value, key) in dashboard" :key="key" class="metric-card"><span>{{ metricLabels[String(key)] ?? key }}</span><strong>{{ value }}</strong></article></div>
      </AsyncState>
      <p v-if="traceIdOf(error)" class="helper-note">接口返回真实错误跟踪号：{{ traceIdOf(error) }}</p>
      <div class="quick-links"><RouterLink v-for="link in quickLinks" :key="link.path" :to="link.path">{{ link.title }}</RouterLink></div>
    </section>

    <section v-else-if="isObservability" class="observability-page">
      <p class="eyebrow">受限运维入口</p><h2>运行状态与监控</h2>
      <p>此页面不代理 Prometheus 原始端点，也不提供模拟监控数据。生产环境需由部署配置注入受权的 HTTPS Grafana 地址。</p>
      <p class="environment-label">当前前端构建环境：{{ buildMode }}</p>
      <a v-if="grafanaUrl" class="button" :href="grafanaUrl" target="_blank" rel="noopener noreferrer">打开授权 Grafana</a>
      <p v-else class="empty-inline">尚未配置受权的 HTTPS Grafana 地址。</p>
    </section>

    <WorkbenchModulePage v-else />
  </WorkbenchLayout>
</template>
