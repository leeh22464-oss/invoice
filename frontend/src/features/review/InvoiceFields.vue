<script setup lang="ts">
import { ref, watch } from 'vue'

import { fieldLabel } from '../../lib/labels'
import { approvalBlocked, type InvoiceRecord } from './types'

const props = defineProps<{
  invoice: InvoiceRecord
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
    <h3>字段</h3>
    <div v-for="(value, name) in draft" :key="name" class="field" :data-field="name">
      <label :for="`field-${name}`">{{ fieldLabel(String(name)) }}</label>
      <input
        :id="`field-${name}`"
        :name="String(name)"
        :value="value"
        @input="updateField(String(name), $event)"
      />
      <p v-if="isLow(String(name))" class="confidence-flag">低置信度</p>
    </div>
    <div class="actions">
      <button class="button-primary" type="button" data-action="save" @click="save">保存修正</button>
      <button
        class="button-quiet"
        type="button"
        data-action="approve"
        :disabled="approvalBlocked(invoice)"
        @click="approve"
      >
        通过
      </button>
      <button class="button-quiet" type="button" data-action="retry" @click="emit('retry')">重试</button>
    </div>
  </section>
</template>
