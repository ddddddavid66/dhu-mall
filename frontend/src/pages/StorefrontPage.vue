<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import StorefrontLayout from '../layouts/StorefrontLayout.vue'
import CatalogPage from './storefront/CatalogPage.vue'
import MemberPage from './storefront/MemberPage.vue'

const route = useRoute()
const publicPage = computed(() => ['home', 'search', 'product'].includes(String(route.meta.page)))
</script>

<template>
  <StorefrontLayout>
    <CatalogPage v-if="publicPage" />
    <MemberPage v-else />
  </StorefrontLayout>
</template>
