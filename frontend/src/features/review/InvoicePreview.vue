<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'

import { api, readApiError } from '../../lib/api'

const props = defineProps<{
  invoiceId: string
}>()

const kind = ref<'image' | 'pdf' | 'file' | 'error' | ''>('')
const objectUrl = ref('')
const filename = ref('票据文件')
const errorMessage = ref('')
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

function readBlobText(blob: Blob): Promise<string> {
  if (typeof blob.text === 'function') {
    return blob.text()
  }
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(typeof reader.result === 'string' ? reader.result : '')
    reader.onerror = () => reject(reader.error)
    reader.readAsText(blob)
  })
}

async function readPreviewError(error: unknown): Promise<string> {
  const data = error && typeof error === 'object' && 'response' in error
    ? (error as { response?: { data?: unknown } }).response?.data
    : undefined
  if (data instanceof Blob) {
    try {
      const parsed: unknown = JSON.parse(await readBlobText(data))
      if (parsed && typeof parsed === 'object') {
        return readApiError({
          isAxiosError: true,
          response: { data: parsed },
        }).message
      }
    } catch {
      // The blob was not a JSON error body. Use the existing fallback copy.
    }
  }
  return readApiError(error).message
}

async function mediaKind(blob: Blob, contentType: string, name: string): Promise<'image' | 'pdf' | 'file'> {
  const type = contentType.split(';')[0].trim().toLowerCase()
  if (type === 'image/jpeg' || type === 'image/jpg' || type === 'image/png') {
    return 'image'
  }
  if (type === 'application/pdf') {
    return 'pdf'
  }
  const lower = name.toLowerCase()
  if (lower.endsWith('.pdf')) {
    return 'pdf'
  }
  if (lower.endsWith('.png') || lower.endsWith('.jpg') || lower.endsWith('.jpeg')) {
    return 'image'
  }
  const bytes = new Uint8Array(await blob.slice(0, 5).arrayBuffer())
  if (bytes[0] === 0xff && bytes[1] === 0xd8) {
    return 'image'
  }
  if (bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47) {
    return 'image'
  }
  if (bytes[0] === 0x25 && bytes[1] === 0x50 && bytes[2] === 0x44 && bytes[3] === 0x46) {
    return 'pdf'
  }
  return 'file'
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
  errorMessage.value = ''
  try {
    const response = await api.get<Blob>(`/api/invoices/${id}/file`, { responseType: 'blob' })
    if (serial !== requestSerial) {
      return
    }
    const contentType = headerValue(response.headers, 'content-type').split(';')[0].trim().toLowerCase()
    const parsedName = filenameFrom(headerValue(response.headers, 'content-disposition'))
    filename.value = parsedName || '票据文件'
    revoke()
    if (!(response.data instanceof Blob)) {
      kind.value = 'file'
      return
    }
    const media = await mediaKind(response.data, contentType, filename.value)
    if (serial !== requestSerial) {
      return
    }
    if (media === 'image' || media === 'pdf') {
      const typed = media === 'pdf'
        ? new Blob([response.data], { type: 'application/pdf' })
        : response.data
      objectToRevoke = URL.createObjectURL(typed)
      objectUrl.value = objectToRevoke
      kind.value = media
      return
    }
    kind.value = 'file'
  } catch (error) {
    if (serial !== requestSerial) {
      return
    }
    revoke()
    const message = await readPreviewError(error)
    if (serial !== requestSerial) {
      return
    }
    kind.value = 'error'
    filename.value = '票据文件'
    errorMessage.value = message
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
    <div class="preview-sheet" :class="{ 'is-document': kind === 'image' || kind === 'pdf' }">
      <h3 v-if="kind !== 'image' && kind !== 'pdf'" class="result-kicker">原票</h3>
      <img v-if="kind === 'image'" :src="objectUrl" alt="票据预览" />
      <iframe
        v-else-if="kind === 'pdf'"
        class="preview-frame"
        :src="`${objectUrl}#toolbar=0&navpanes=0&view=FitH`"
        :title="filename"
      ></iframe>
      <p v-else-if="kind === 'file'">
        <a class="button-quiet" :href="`/api/invoices/${invoiceId}/file`">{{ filename }}</a>
      </p>
      <p v-else-if="kind === 'error'" class="preview-error" aria-live="assertive">{{ errorMessage }}</p>
      <p v-else class="muted">正在读取原票</p>
    </div>
  </section>
</template>
