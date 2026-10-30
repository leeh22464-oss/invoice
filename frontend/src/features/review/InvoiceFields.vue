<script setup lang="ts">
import { ref, watch } from 'vue'

import { fieldLabel, fieldValue, statusLabel } from '../../lib/labels'
import { approvalBlocked, type InvoiceRecord } from './types'

const props = defineProps<{
  invoice: InvoiceRecord
  retrying?: boolean
}>()

const emit = defineEmits<{
  save: [fields: Record<string, string>]
  approve: []
  retry: []
}>()

const draft = ref<Record<string, string>>({})

watch(() => props.invoice, (invoice) => {
  draft.value = { ...invoice.fields }
}, { immediate: true })

function isLow(name: string): boolean {
  const value = props.invoice.fieldConfidence?.[name]
  return typeof value === 'number' && value < 0.80
}

function updateField(name: string, event: Event) {
  const target = event.target as HTMLInputElement
  draft.value = { ...draft.value, [name]: target.value }
}

function save() {
  emit('save', { ...draft.value })
}

function approve() {
  if (approvalBlocked(props.invoice)) {
    return
  }
  emit('approve')
}
</script>

<template>
  <section class="fields-panel" aria-label="票据字段">
    <div class="result-head">
      <h3>识别结果</h3>
      <span class="result-status"><i class="status-dot" :data-status="invoice.status">●</i> {{ statusLabel(invoice.status) }}</span>
    </div>
    <div v-for="(value, name) in draft" :key="name" class="field" :data-field="name">
      <label :for="`field-${name}`">{{ fieldLabel(String(name)) }}</label>
      <input
        :id="`field-${name}`"
        :name="String(name)"
        :value="fieldValue(String(name), value)"
        @input="updateField(String(name), $event)"
      />
      <p v-if="isLow(String(name))" class="confidence-flag">低置信度</p>
    </div>
    <div class="actions">
      <button class="button-quiet" type="button" data-action="save" @click="save">保存修正</button>
      <button
        class="button-quiet"
        type="button"
        data-action="approve"
        :disabled="approvalBlocked(invoice)"
        @click="approve"
      >
        通过
      </button>
      <button
        class="button-quiet"
        type="button"
        data-action="retry"
        :disabled="retrying"
        @click="emit('retry')"
      >
        {{ retrying ? '正在重新识别' : '重试' }}
      </button>
    </div>
  </section>
</template>
