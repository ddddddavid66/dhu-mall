<script setup lang="ts">
defineProps<{ value: string }>()
</script>

<template><span class="money"><span class="currency">¥</span>{{ value }}</span></template>
