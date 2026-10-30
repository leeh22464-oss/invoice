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
const retrying = ref(false)
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
const greeting = computed(() => {
  const hour = new Date().getHours()
  const hello = hour < 12 ? '上午好' : hour < 18 ? '下午好' : '晚上好'
  return `${hello}，${invoices.value.length} 张发票待处理`
})

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
  if (!current || retrying.value) {
    return
  }
  const beforeType = current.invoiceType
  const beforeNumber = current.invoiceNumber
  liveMessage.value = ''
  retrying.value = true
  try {
    const { data } = await api.post<InvoiceRecord>(`/api/invoices/${current.id}/retry`)
    replace(data)
    if (data.invoiceType === beforeType && data.invoiceNumber === beforeNumber && data.invoiceType === 'UNKNOWN') {
      liveMessage.value = '重新识别完成，仍未读到发票内容'
    } else {
      liveMessage.value = '已重新识别'
    }
  } catch (error) {
    liveMessage.value = readApiError(error).message
  } finally {
    retrying.value = false
  }
}
</script>

<template>
  <div class="workbench-wrap">
    <header class="desk-head">
      <div>
        <p class="eyebrow">核对工作台</p>
        <h2>{{ greeting }}</h2>
      </div>
    </header>
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
        class="workbench-queue"
        :class="{ 'is-open': queueOpen }"
        :inert="queueInert ? true : undefined"
      >
        <ReviewQueue :invoices="invoices" :selected-index="selectedIndex" @select="select" />
      </div>
      <InvoicePreview v-if="selected" class="workbench-preview" :invoice-id="selected.id" />
      <section v-else class="workbench-preview" aria-label="票据预览">
        <div class="preview-sheet">
          <h3 class="result-kicker">原票</h3>
          <p class="muted">暂无待复核票据</p>
        </div>
      </section>
      <aside class="workbench-detail">
        <template v-if="selected">
          <InvoiceFields
            :invoice="selected"
            :retrying="retrying"
            @save="save"
            @approve="approve"
            @retry="retry"
          />
          <VerificationIssues :issues="selected.issues ?? []" />
          <DuplicateEvidence :evidence="selected.duplicateEvidence ?? null" />
        </template>
        <template v-else>
          <div class="result-head">
            <h3>识别结果</h3>
          </div>
          <p class="muted">从左侧队列选择一张票据。</p>
        </template>
        <p class="live-error" aria-live="assertive">{{ liveMessage }}</p>
      </aside>
    </section>
  </div>
</template>
