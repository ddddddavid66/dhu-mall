<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter, type LocationQueryRaw } from 'vue-router'
import AsyncState from '../../components/AsyncState.vue'
import MoneyText from '../../components/MoneyText.vue'
import { callOperation } from '../../api/client'
import type { CategoryView, Page, ProductView, ReviewView } from '../../api/models'
import { useSessionStore } from '../../stores/session'

const route = useRoute()
const router = useRouter()
const session = useSessionStore()
const pageType = computed(() => String(route.meta.page))
const loading = ref(false)
const error = ref<unknown>(null)
const actionError = ref<unknown>(null)
const products = ref<Page<ProductView> | null>(null)
const categories = ref<CategoryView[]>([])
const product = ref<ProductView | null>(null)
const reviews = ref<Page<ReviewView> | null>(null)
const selectedQuantity = ref(1)
const adding = ref(false)
const searchKeyword = ref(String(route.query.keyword ?? ''))
const selectedCategory = ref(String(route.query.categoryId ?? ''))
const selectedSort = ref(String(route.query.sort ?? 'NEWEST'))
let activeRequest: AbortController | null = null
let searchDebounce: ReturnType<typeof setTimeout> | undefined

const title = computed(() => pageType.value === 'home' ? '把下一本好书带回家' : '找到你想读的那一本')
const flattenedCategories = computed(() => categories.value.flatMap(category => [category, ...category.children.filter(child => child.status === 'ACTIVE')]).filter(item => item.status === 'ACTIVE'))
const searchShopName = computed(() => products.value?.items.find(item => item.shopId === route.query.shopId)?.shopName ?? '')

function queryForProducts() {
  const query: Record<string, string | number | boolean> = { page: Number(route.query.page ?? 1), pageSize: 20 }
  if (pageType.value === 'home') query.featured = true
  if (typeof route.query.keyword === 'string' && route.query.keyword) query.keyword = route.query.keyword
  if (typeof route.query.categoryId === 'string' && route.query.categoryId) query.categoryId = route.query.categoryId
  if (typeof route.query.shopId === 'string' && route.query.shopId) query.shopId = route.query.shopId
  if (typeof route.query.sort === 'string') query.sort = route.query.sort
  return query
}

async function load() {
  activeRequest?.abort()
  activeRequest = new AbortController()
  loading.value = true
  error.value = null
  try {
    if (pageType.value === 'product') {
      const id = String(route.params.id)
      const [detail, reviewPage] = await Promise.all([
        callOperation<ProductView>('API-13', { path: { id }, signal: activeRequest.signal }),
        callOperation<Page<ReviewView>>('API-14', { path: { id }, query: { page: 1, pageSize: 20 }, signal: activeRequest.signal }),
      ])
      product.value = detail
      reviews.value = reviewPage
    } else {
      products.value = await callOperation<Page<ProductView>>('API-12', { query: queryForProducts(), signal: activeRequest.signal })
      if (categories.value.length === 0) categories.value = await callOperation<CategoryView[]>('API-11', { signal: activeRequest.signal })
    }
  } catch (cause) {
    if (!(cause instanceof DOMException && cause.name === 'AbortError')) error.value = cause
  } finally {
    if (!activeRequest.signal.aborted) loading.value = false
  }
}

async function applyFilters() {
  const query: LocationQueryRaw = { ...route.query, page: undefined }
  if (searchKeyword.value.trim()) query.keyword = searchKeyword.value.trim()
  else delete query.keyword
  if (selectedCategory.value) query.categoryId = selectedCategory.value
  else delete query.categoryId
  if (selectedSort.value && selectedSort.value !== 'NEWEST') query.sort = selectedSort.value
  else delete query.sort
  await router.replace({ path: '/search', query })
}

function scheduleFilterReload() {
  if (pageType.value !== 'search') return
  if (searchDebounce) clearTimeout(searchDebounce)
  searchDebounce = setTimeout(() => void applyFilters(), 300)
}

async function addToCart() {
  if (!session.isSignedIn) {
    await router.push({ name: 'login', query: { returnTo: route.fullPath } })
    return
  }
  if (!product.value) return
  adding.value = true
  actionError.value = null
  try {
    await callOperation('API-24', { path: { skuId: product.value.skuId }, body: { quantity: selectedQuantity.value, selected: true } })
    await router.push('/cart')
  } catch (cause) { actionError.value = cause }
  finally { adding.value = false }
}

async function buyNow() {
  if (!session.isSignedIn) {
    await router.push({ name: 'login', query: { returnTo: route.fullPath } })
    return
  }
  if (!product.value) return
  adding.value = true
  actionError.value = null
  try {
    await callOperation('API-24', { path: { skuId: product.value.skuId }, body: { quantity: selectedQuantity.value, selected: true } })
    await router.push({ path: '/checkout', query: { buyNowSkuId: product.value.skuId } })
  } catch (cause) { actionError.value = cause }
  finally { adding.value = false }
}

async function chooseProductShop(shopId: string) {
  await router.push({ path: '/search', query: { ...route.query, shopId } })
}

function changePage(page: number) {
  void router.push({ path: route.path, query: { ...route.query, page } })
}

onMounted(() => { void load() })
watch(() => route.fullPath, () => {
  searchKeyword.value = String(route.query.keyword ?? '')
  selectedCategory.value = String(route.query.categoryId ?? '')
  selectedSort.value = String(route.query.sort ?? 'NEWEST')
  void load()
})
onBeforeUnmount(() => { activeRequest?.abort(); if (searchDebounce) clearTimeout(searchDebounce) })
</script>

<template>
  <section v-if="pageType !== 'product'" class="catalog-page">
    <div v-if="pageType === 'home'" class="store-hero">
      <p class="eyebrow">阅集 · 多家独立书店</p>
      <h1>{{ title }}</h1>
      <p>同一本书，看看不同书店的真实报价与库存。每家店铺分别发货，订单与支付彼此独立。</p>
      <RouterLink class="button" to="/search">浏览全部图书 <span aria-hidden="true">→</span></RouterLink>
    </div>
    <div v-else class="page-heading">
      <p class="eyebrow">图书目录</p><h1>{{ title }}</h1>
      <p v-if="route.query.keyword">“{{ route.query.keyword }}” 的搜索结果</p>
      <p v-else-if="route.query.shopId">店铺：{{ searchShopName || '所选店铺' }}</p>
    </div>

    <form v-if="pageType === 'search'" class="filter-bar" @submit.prevent="applyFilters">
      <label>分类
        <select v-model="selectedCategory" @change="scheduleFilterReload">
          <option value="">全部分类</option>
          <option v-for="category in flattenedCategories" :key="category.id" :value="category.id">{{ category.name }}</option>
        </select>
      </label>
      <label>排序
        <select v-model="selectedSort" @change="scheduleFilterReload">
          <option value="NEWEST">最新上架</option><option value="PRICE_ASC">价格从低到高</option><option value="PRICE_DESC">价格从高到低</option>
        </select>
      </label>
      <button class="button button-secondary" type="submit">应用筛选</button>
      <button v-if="route.query.keyword || route.query.categoryId || route.query.shopId" class="text-button" type="button" @click="router.replace('/search')">清空筛选</button>
      <span class="result-count">共 {{ products?.total ?? '—' }} 本</span>
    </form>

    <AsyncState :loading="loading" :error="error" :empty="!loading && !error && (products?.items.length ?? 0) === 0" empty-text="没有符合条件的图书，试试清空筛选或更换关键词。" @retry="load">
      <div class="book-grid">
        <article v-for="item in products?.items" :key="item.id" class="book-card">
          <RouterLink class="book-cover" :to="`/products/${item.id}`" :aria-label="`查看《${item.book.title}》`">
            <img v-if="item.book.coverUrl" :src="item.book.coverUrl" :alt="`《${item.book.title}》封面`" loading="lazy" />
            <span v-else class="cover-placeholder" aria-label="暂无封面"><span>阅集</span><strong>{{ item.book.title }}</strong><small>{{ item.book.author }}</small></span>
          </RouterLink>
          <div class="book-copy">
            <RouterLink class="book-title" :to="`/products/${item.id}`">{{ item.book.title }}</RouterLink>
            <p class="book-author">{{ item.book.author }} <span v-if="item.book.publisher">· {{ item.book.publisher }}</span></p>
            <button class="shop-link" type="button" @click="chooseProductShop(item.shopId)">{{ item.shopName }}</button>
            <div class="book-card-bottom"><MoneyText :value="item.salePrice" /><span v-if="item.availableQuantity === 0" class="stock-tag stock-out">暂时缺货</span><span v-else class="stock-tag">有货</span></div>
          </div>
        </article>
      </div>
      <nav v-if="products && products.total > products.pageSize" class="pagination" aria-label="图书分页">
        <button class="button button-secondary" :disabled="products.page <= 1" @click="changePage(products.page - 1)">上一页</button>
        <span>第 {{ products.page }} 页 · 共 {{ products.total }} 本</span>
        <button class="button button-secondary" :disabled="products.page * products.pageSize >= products.total" @click="changePage(products.page + 1)">下一页</button>
      </nav>
    </AsyncState>
  </section>

  <section v-else class="product-page">
    <AsyncState :loading="loading" :error="error" :empty="!product" empty-text="没有找到这本书，或它当前不可公开购买。" @retry="load">
      <template v-if="product">
        <RouterLink class="back-link" to="/search">← 返回图书目录</RouterLink>
        <div class="product-detail">
          <div class="product-cover-frame">
            <img v-if="product.book.coverUrl" :src="product.book.coverUrl" :alt="`《${product.book.title}》封面`" />
            <div v-else class="cover-placeholder cover-large"><span>阅集</span><strong>{{ product.book.title }}</strong><small>{{ product.book.author }}</small></div>
          </div>
          <div class="product-information">
            <p class="eyebrow">{{ product.book.categoryName }} · {{ product.book.publisher }}</p>
            <h1>{{ product.book.title }}</h1>
            <p class="product-author">{{ product.book.author }}</p>
            <p class="isbn-line">ISBN {{ product.book.isbn }}<span v-if="product.book.publicationDate"> · {{ product.book.publicationDate }}</span></p>
            <div class="product-description">{{ product.book.description || '书籍介绍暂未提供。' }}</div>
            <div class="offer-card">
              <div><span class="muted">{{ product.shopName }} · 实体新书</span><p class="offer-price"><MoneyText :value="product.salePrice" /></p></div>
              <span v-if="product.availableQuantity > 0" class="stock-tag">现货 {{ product.availableQuantity }}</span><span v-else class="stock-tag stock-out">暂时缺货</span>
              <label class="quantity-control">数量 <input v-model.number="selectedQuantity" type="number" min="1" :max="Math.max(1, Math.min(99, product.availableQuantity))" :disabled="product.availableQuantity < 1" /></label>
              <p v-if="actionError" class="inline-error" role="alert">{{ actionError instanceof Error ? actionError.message : '操作失败，请重试。' }}</p>
              <div class="button-row"><button class="button button-secondary" type="button" :disabled="adding || product.availableQuantity < 1" @click="addToCart">{{ adding ? '正在处理…' : '加入购物袋' }}</button><button class="button" type="button" :disabled="adding || product.availableQuantity < 1" @click="buyNow">{{ adding ? '正在处理…' : '立即购买' }}</button></div>
            </div>
            <p class="helper-note">首版包邮；来自不同书店的商品将生成独立订单和支付。</p>
          </div>
        </div>
        <section class="review-section" aria-labelledby="reviews-heading">
          <div class="section-title"><div><p class="eyebrow">读者书评</p><h2 id="reviews-heading">真实阅读感受</h2></div><span>{{ reviews?.total ?? 0 }} 条</span></div>
          <AsyncState :loading="false" :empty="(reviews?.items.length ?? 0) === 0" empty-text="这本书还没有公开评价。">
            <article v-for="review in reviews?.items" :key="review.id" class="review-card">
              <div><strong>{{ review.nickname }}</strong><span class="rating" :aria-label="`${review.rating} 星`">{{ '★'.repeat(review.rating) }}{{ '☆'.repeat(5 - review.rating) }}</span><time>{{ new Date(review.createdAt).toLocaleDateString('zh-CN') }}</time></div>
              <p>{{ review.content }}</p>
            </article>
          </AsyncState>
        </section>
      </template>
    </AsyncState>
  </section>
</template>
