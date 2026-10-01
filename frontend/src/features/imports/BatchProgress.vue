<script setup lang="ts">
import { statusLabel } from '../../lib/labels'

defineProps<{
  totalFiles: number
  status: string
  statusCounts: Record<string, number>
}>()
</script>

<template>
  <section class="batch-progress" aria-label="导入进度">
    <h3>导入进度</h3>
    <p>共 {{ totalFiles }} 个文件</p>
    <p>批次状态 {{ statusLabel(status) }}</p>
    <ul class="status-list">
      <li v-for="(count, name) in statusCounts" :key="name">
        <span>{{ statusLabel(String(name)) }}</span>
        <span>{{ count }}</span>
      </li>
    </ul>
  </section>
</template>
