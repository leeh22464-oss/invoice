<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'

import { api } from '../../lib/api'

const props = defineProps<{
  invoiceId: string
}>()

const kind = ref<'image' | 'file' | ''>('')
const objectUrl = ref('')
const filename = ref('票据文件')
let objectToRevoke = ''
let requestSerial = 0

function headerValue(headers: unknown, name: string): string {
  if (!headers || typeof headers !== 'object') {
    return ''
  }
  const bag = headers as { get?: (headerName: string) => unknown }
  const raw = typeof bag.get === 'function'
    ? bag.get(name)
    : (headers as Record<string, unknown>)[name]
  if (typeof raw === 'string') {
    return raw
  }
  if (Array.isArray(raw)) {
    return String(raw[0] ?? '')
  }
  return ''
}

function filenameFrom(disposition: string): string {
  const encoded = disposition.match(/filename\*=UTF-8''([^;]+)/i)
  if (encoded?.[1]) {
    try {
      return decodeURIComponent(encoded[1].trim())
    } catch {
      return encoded[1].trim()
    }
  }
  const quoted = disposition.match(/filename="([^"]+)"/i)
  if (quoted?.[1]) {
    return quoted[1]
  }
  const plain = disposition.match(/filename=([^;]+)/i)
  return plain?.[1]?.trim() ?? ''
}

function revoke() {
  if (objectToRevoke) {
    URL.revokeObjectURL(objectToRevoke)
    objectToRevoke = ''
  }
  objectUrl.value = ''
}

async function load(id: string) {
  const serial = ++requestSerial
  kind.value = ''
  try {
    const response = await api.get<Blob>(`/api/invoices/${id}/file`, { responseType: 'blob' })
    if (serial !== requestSerial) {
      return
    }
    const contentType = headerValue(response.headers, 'content-type').split(';')[0].trim().toLowerCase()
    const parsedName = filenameFrom(headerValue(response.headers, 'content-disposition'))
    filename.value = parsedName || '票据文件'
    revoke()
    const image = contentType === 'image/jpeg' || contentType === 'image/jpg' || contentType === 'image/png'
    if (image && response.data instanceof Blob) {
      objectToRevoke = URL.createObjectURL(response.data)
      objectUrl.value = objectToRevoke
      kind.value = 'image'
      return
    }
    kind.value = 'file'
  } catch {
    if (serial !== requestSerial) {
      return
    }
    revoke()
    kind.value = ''
    filename.value = '票据文件'
  }
}

watch(() => props.invoiceId, (id) => {
  if (id) {
    void load(id)
  }
}, { immediate: true })

onUnmounted(() => {
  requestSerial += 1
  revoke()
})
</script>

<template>
  <section data-preview class="preview-panel" aria-label="票据预览">
    <h3>原票</h3>
    <img v-if="kind === 'image'" :src="objectUrl" alt="票据预览" />
    <p v-else-if="kind === 'file'">
      <a :href="`/api/invoices/${invoiceId}/file`">{{ filename }}</a>
    </p>
    <p v-else class="muted">正在读取原票</p>
  </section>
</template>
