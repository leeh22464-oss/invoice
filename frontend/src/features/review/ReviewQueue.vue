<script setup lang="ts">
import { invoiceTypeLabel, statusLabel } from '../../lib/labels'
import type { InvoiceRecord } from './types'

const props = defineProps<{
  invoices: InvoiceRecord[]
  selectedIndex: number
}>()

const emit = defineEmits<{
  select: [index: number]
}>()

function onKeydown(event: KeyboardEvent) {
  if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') {
    return
  }
  event.preventDefault()
  if (props.invoices.length === 0) {
    return
  }
  const next = event.key === 'ArrowDown'
    ? Math.min(props.selectedIndex + 1, props.invoices.length - 1)
    : Math.max(props.selectedIndex - 1, 0)
  emit('select', next)
}
</script>

<template>
  <div
    class="review-queue"
    role="listbox"
    tabindex="0"
    aria-label="待复核队列"
    @keydown="onKeydown"
  >
    <h3>待复核</h3>
    <p v-if="invoices.length === 0" class="muted">暂无票据</p>
    <button
      v-for="(invoice, index) in invoices"
      :key="invoice.id"
      class="queue-item"
      :class="{ 'is-selected': index === selectedIndex }"
      type="button"
      role="option"
      :aria-selected="index === selectedIndex"
      @click="emit('select', index)"
    >
      <span>{{ invoice.invoiceNumber || '未识别号码' }}</span>
      <span class="muted">{{ invoiceTypeLabel(invoice.invoiceType) }} · {{ statusLabel(invoice.status) }}</span>
    </button>
  </div>
</template>
