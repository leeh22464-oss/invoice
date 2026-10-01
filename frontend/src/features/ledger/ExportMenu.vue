<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  query: string
}>()

const csvHref = computed(() => props.query ? `/api/ledger/export.csv?${props.query}` : '/api/ledger/export.csv')
const xlsxHref = computed(() => props.query ? `/api/ledger/export.xlsx?${props.query}` : '/api/ledger/export.xlsx')
</script>

<template>
  <nav class="export-menu" aria-label="导出">
    <a data-export="csv" :href="csvHref">导出 CSV</a>
    <a data-export="xlsx" :href="xlsxHref">导出 Excel</a>
  </nav>
</template>
