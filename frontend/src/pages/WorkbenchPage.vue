<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import AsyncState from '../components/AsyncState.vue'
import WorkbenchLayout from '../layouts/WorkbenchLayout.vue'
import { ApiError, callOperation } from '../api/client'
import type { ApiRow } from '../api/models'
import WorkbenchModulePage from './workbench/WorkbenchModulePage.vue'

const route = useRoute()
const path = computed(() => route.path)
const isDashboard = computed(() => path.value === '/workbench')
const isObservability = computed(() => path.value === '/workbench/observability')
const buildMode = import.meta.env.MODE
const loading = ref(false)
const error = ref<unknown>(null)
const dashboard = ref<ApiRow | null>(null)
const grafanaUrl = computed(() => {
  const candidate = (import.meta.env as ImportMetaEnv & { VITE_GRAFANA_URL?: string }).VITE_GRAFANA_URL
  if (!candidate) return ''
  try {
    const url = new URL(candidate)
    return url.protocol === 'https:' && !url.username && !url.password ? url.toString() : ''
  } catch { return '' }
})

async function loadDashboard() {
  loading.value = true
  error.value = null
  try { dashboard.value = await callOperation<ApiRow>('API-89') }
  catch (cause) { error.value = cause }
  finally { loading.value = false }
}

function traceIdOf(value: unknown) { return value instanceof ApiError ? value.result.traceId : '' }
onMounted(() => { if (isDashboard.value) void loadDashboard() })
watch(isDashboard, value => { if (value) void loadDashboard() })
</script>

<template>
  <WorkbenchLayout>
    <section v-if="isDashboard" class="dashboard-page">
      <p class="eyebrow">阅集 · 管理工作台</p><h2>业务入口</h2>
      <p class="dashboard-intro">订单、商品和财务数据由各自授权的服务页面读取。仪表盘只在后端聚合接口实际可用时展示数据。</p>
      <AsyncState :loading="loading" :error="error" :empty="!dashboard" empty-text="后台汇总指标接口尚未实现，当前不会展示虚构统计。请从下方授权业务页面查看实时记录。" empty-action-text="重新查询 API-89" @retry="loadDashboard">
        <div class="metric-grid"><article v-for="(value, key) in dashboard" :key="key" class="metric-card"><span>{{ key }}</span><strong>{{ value }}</strong></article></div>
      </AsyncState>
      <p v-if="traceIdOf(error)" class="helper-note">接口返回真实错误跟踪号：{{ traceIdOf(error) }}</p>
      <div class="quick-links"><RouterLink to="/workbench/merchant/orders">订单履约</RouterLink><RouterLink to="/workbench/support/after-sales">售后处理</RouterLink><RouterLink to="/workbench/finance/reconciliation">渠道对账</RouterLink><RouterLink to="/workbench/finance/settlements">结算账单</RouterLink></div>
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
