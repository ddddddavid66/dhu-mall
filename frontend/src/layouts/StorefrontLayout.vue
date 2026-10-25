<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { callOperation } from '../api/client'
import type { CategoryView } from '../api/models'
import { useSessionStore } from '../stores/session'

const route = useRoute()
const router = useRouter()
const session = useSessionStore()
const keyword = ref(String(route.query.keyword ?? ''))
const categories = ref<CategoryView[]>([])
const categoryError = ref(false)
const accountLabel = computed(() => session.user?.nickname || '登录 / 注册')
const hasBackOffice = computed(() => session.user?.roles.some(role => role !== 'MEMBER') ?? false)
async function submitSearch() {
  await router.push({ path: '/search', query: keyword.value.trim() ? { keyword: keyword.value.trim() } : {} })
}
onMounted(async () => {
  try { categories.value = await callOperation<CategoryView[]>('API-11') }
  catch { categoryError.value = true }
})
</script>

<template>
  <div class="store-shell">
    <header class="store-header">
      <RouterLink class="brand" to="/" aria-label="阅集图书商城首页">阅集<span>图书商城</span></RouterLink>
      <form class="global-search" role="search" @submit.prevent="submitSearch">
        <label class="sr-only" for="global-search-input">搜索书名、作者或 ISBN</label>
        <input id="global-search-input" v-model="keyword" type="search" placeholder="搜书名、作者或 ISBN" />
        <button class="search-button" type="submit" aria-label="搜索">⌕</button>
      </form>
      <div class="header-links">
        <RouterLink to="/cart" class="header-link">购物袋</RouterLink>
        <RouterLink v-if="session.isSignedIn" to="/account/orders" class="header-link">我的订单</RouterLink>
        <RouterLink v-else to="/login" class="header-link account-link">{{ accountLabel }}</RouterLink>
        <details v-if="session.isSignedIn" class="account-menu">
          <summary>{{ accountLabel }}　⌄</summary>
          <div class="account-popover">
            <RouterLink to="/account/orders">订单与售后</RouterLink>
            <RouterLink to="/account/addresses">收货地址</RouterLink>
            <RouterLink to="/account/security">账户安全</RouterLink>
            <button type="button" @click="session.logout">退出登录</button>
            <RouterLink v-if="hasBackOffice" to="/workbench">管理工作台</RouterLink>
          </div>
        </details>
      </div>
      <nav class="category-strip" aria-label="图书分类">
        <RouterLink to="/search">全部图书</RouterLink>
        <template v-if="!categoryError">
          <RouterLink v-for="category in categories.filter(item => item.status === 'ACTIVE').slice(0, 8)" :key="category.id" :to="{ path: '/search', query: { categoryId: category.id } }">
            {{ category.name }}
          </RouterLink>
        </template>
      </nav>
    </header>
    <main class="store-content"><slot /></main>
    <footer class="store-footer"><span>阅集 · 让好书找到读者</span><span>实体新书 · 店铺独立发货与支付</span></footer>
  </div>
</template>
