<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'

import { api, readApiError } from '../../lib/api'
import DuplicateEvidence from './DuplicateEvidence.vue'
import InvoiceFields from './InvoiceFields.vue'
import InvoicePreview from './InvoicePreview.vue'
import ReviewQueue from './ReviewQueue.vue'
import { approvalBlocked, type InvoiceRecord } from './types'
import VerificationIssues from './VerificationIssues.vue'

const invoices = ref<InvoiceRecord[]>([])
const selectedIndex = ref(0)
const liveMessage = ref('')
const queueOpen = ref(false)
const narrowQuery = '(max-width: 1099px)'
const narrowMedia = typeof window.matchMedia === 'function' ? window.matchMedia(narrowQuery) : null
const narrow = ref(narrowMedia?.matches ?? false)

function onNarrowChange(event: MediaQueryListEvent) {
  narrow.value = event.matches
}

if (narrowMedia) {
  narrowMedia.addEventListener('change', onNarrowChange)
}

onUnmounted(() => {
  narrowMedia?.removeEventListener('change', onNarrowChange)
})

const selected = computed(() => invoices.value[selectedIndex.value])
const queueInert = computed(() => narrow.value && !queueOpen.value)

function replace(next: InvoiceRecord) {
  const index = invoices.value.findIndex((item) => item.id === next.id)
  if (index === -1) {
    return
  }
  invoices.value.splice(index, 1, next)
}

onMounted(async () => {
  try {
    const { data } = await api.get<InvoiceRecord[]>('/api/invoices')
    invoices.value = data
    selectedIndex.value = 0
  } catch (error) {
    liveMessage.value = readApiError(error).message
  }
})

function select(index: number) {
  selectedIndex.value = index
}

async function save(fields: Record<string, string>) {
  const current = selected.value
  if (!current) {
    return
  }
  liveMessage.value = ''
  try {
    const { data } = await api.patch<InvoiceRecord>(`/api/invoices/${current.id}`, { fields })
    replace(data)
  } catch (error) {
    liveMessage.value = readApiError(error).message
  }
}

async function approve() {
  const current = selected.value
  if (!current || approvalBlocked(current)) {
    return
  }
  liveMessage.value = ''
  try {
    const { data } = await api.patch<InvoiceRecord>(`/api/invoices/${current.id}`, { status: 'PASSED' })
    replace(data)
  } catch (error) {
    liveMessage.value = readApiError(error).message
  }
}

async function retry() {
  const current = selected.value
  if (!current) {
    return
  }
  liveMessage.value = ''
  try {
    const { data } = await api.post<InvoiceRecord>(`/api/invoices/${current.id}/retry`)
    replace(data)
  } catch (error) {
    liveMessage.value = readApiError(error).message
  }
}
</script>

<template>
  <div class="card workbench-wrap">
    <button
      class="button-quiet queue-toggle"
      type="button"
      aria-controls="review-queue"
      :aria-expanded="queueOpen"
      @click="queueOpen = !queueOpen"
    >
      队列
    </button>
    <section class="workbench">
      <div
        id="review-queue"
        class="workbench-queue card"
        :class="{ 'is-open': queueOpen }"
        :inert="queueInert ? true : undefined"
      >
        <ReviewQueue :invoices="invoices" :selected-index="selectedIndex" @select="select" />
      </div>
      <template v-if="selected">
        <InvoicePreview class="workbench-preview card" :invoice-id="selected.id" />
        <aside class="workbench-detail card">
          <InvoiceFields :invoice="selected" @save="save" @approve="approve" @retry="retry" />
          <VerificationIssues :issues="selected.issues ?? []" />
          <DuplicateEvidence :evidence="selected.duplicateEvidence ?? null" />
        </aside>
      </template>
      <p v-else class="muted workbench-empty">暂无待复核票据</p>
    </section>
    <p class="live-error" aria-live="assertive">{{ liveMessage }}</p>
  </div>
</template>
