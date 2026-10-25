<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { ElButton } from 'element-plus'
import { useFoundationStore } from '../stores/foundation'

const foundation = useFoundationStore()
const healthLabel = computed(() => ({
  idle: '尚未检查', loading: '正在检查', up: '工程健康检查通过', unavailable: '暂时无法连接后端',
})[foundation.health])
onMounted(() => foundation.checkHealth())
</script>

<template>
  <div class="foundation-layout">
    <header>
      <RouterLink class="brand" to="/">阅集 <span>图书商城</span></RouterLink>
      <nav aria-label="工程导航">
        <RouterLink to="/">商城入口</RouterLink>
        <RouterLink to="/workbench">工作台入口</RouterLink>
      </nav>
    </header>
    <main>
      <p class="eyebrow">商品与库存接口 · 阶段 C</p>
      <h1>阅集的工程基础已建立</h1>
      <p class="intro">身份、地址与管理接口已开放。商城业务页面将在后续阶段逐步接入。</p>
      <section class="health-panel" aria-labelledby="health-title">
        <h2 id="health-title">开发环境连接</h2>
        <p role="status" aria-live="polite">{{ healthLabel }}</p>
        <p class="muted">此检查表示应用及数据库连接可用，不代表商品与交易业务已就绪。</p>
        <ElButton type="primary" :loading="foundation.health === 'loading'" @click="foundation.checkHealth()">
          重新检查
        </ElButton>
      </section>
      <p class="muted">接口清单共 92 项，其中 46 项身份、商品、库存、文件和购物车操作已实现。当前不会创建订单或发起付款。</p>
    </main>
    <footer>阅集图书商城 · 工程骨架</footer>
  </div>
</template>
