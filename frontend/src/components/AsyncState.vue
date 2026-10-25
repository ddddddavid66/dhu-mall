<script setup lang="ts">
import { computed } from 'vue'
import { ApiError } from '../api/client'

const props = withDefaults(defineProps<{
  loading: boolean
  error?: unknown
  empty?: boolean
  emptyText?: string
  emptyActionText?: string
}>(), { empty: false, emptyText: '这里暂时没有记录。', emptyActionText: '重新加载' })
const emit = defineEmits<{ retry: [] }>()
const message = computed(() => props.error instanceof Error ? props.error.message : '服务暂时不可用，请重试。')
const traceId = computed(() => props.error instanceof ApiError ? props.error.result.traceId : '')
async function copyTraceId() { if (traceId.value) await navigator.clipboard?.writeText(traceId.value) }
</script>

<template>
  <div v-if="loading" class="async-state" role="status" aria-live="polite">
    <span class="spinner" aria-hidden="true"></span><span>正在从商城服务加载…</span>
  </div>
  <section v-else-if="error" class="async-state async-error" role="alert">
    <h2>暂时无法完成请求</h2><p>{{ message }}</p>
    <p v-if="traceId" class="trace-line">跟踪号 <code>{{ traceId }}</code>
      <button class="text-button" type="button" @click="copyTraceId">复制</button>
    </p>
    <button class="button button-secondary" type="button" @click="emit('retry')">{{ emptyActionText }}</button>
  </section>
  <section v-else-if="empty" class="async-state async-empty">
    <div class="empty-mark" aria-hidden="true">阅</div><h2>暂无内容</h2><p>{{ emptyText }}</p>
    <slot name="empty-action" />
  </section>
  <slot v-else />
</template>
