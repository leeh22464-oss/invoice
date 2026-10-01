<script setup lang="ts">
import { onUnmounted, ref } from 'vue'

import { api, readApiError } from '../../lib/api'
import BatchProgress from './BatchProgress.vue'

const MAX_FILES = 100
const MAX_BYTES = 20 * 1024 * 1024
const TYPE_ERROR = '仅支持 JPG、JPEG、PNG 和 PDF'
const SIZE_ERROR = '单个文件不能超过 20 MiB'
const COUNT_ERROR = '一次最多上传 100 个文件'

const EXTENSION_TYPES: Record<string, string> = {
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  png: 'image/png',
  pdf: 'application/pdf',
}

type Progress = {
  totalFiles: number
  status: string
  statusCounts: Record<string, number>
}

const files = ref<File[]>([])
const errors = ref<string[]>([])
const liveMessage = ref('')
const busy = ref(false)
const progress = ref<Progress | null>(null)

let eventSource: EventSource | null = null
let pollTimer: number | undefined

function extensionOf(name: string): string {
  const dot = name.lastIndexOf('.')
  return dot < 0 ? '' : name.slice(dot + 1).toLowerCase()
}

function accepted(file: File): boolean {
  const expected = EXTENSION_TYPES[extensionOf(file.name)]
  if (!expected) {
    return false
  }
  if (!file.type) {
    return true
  }
  return file.type.toLowerCase() === expected
}

function validate(list: File[]): string[] {
  const next: string[] = []
  if (list.length > MAX_FILES) {
    next.push(COUNT_ERROR)
  }
  if (list.some((file) => file.size > MAX_BYTES)) {
    next.push(SIZE_ERROR)
  }
  if (list.some((file) => !accepted(file))) {
    next.push(TYPE_ERROR)
  }
  return next
}

function applyFiles(list: File[]) {
  files.value = list
  errors.value = validate(list)
  liveMessage.value = ''
}

function onInput(event: Event) {
  const input = event.target as HTMLInputElement
  applyFiles(Array.from(input.files ?? []))
}

function onDrop(event: DragEvent) {
  applyFiles(Array.from(event.dataTransfer?.files ?? []))
}

function stopListening() {
  eventSource?.close()
  eventSource = null
  if (pollTimer !== undefined) {
    window.clearInterval(pollTimer)
    pollTimer = undefined
  }
}

function readProgress(raw: unknown): Progress | null {
  const parsed = typeof raw === 'string' ? JSON.parse(raw) as Partial<Progress> : raw as Partial<Progress>
  if (!parsed || typeof parsed !== 'object') {
    return null
  }
  return {
    totalFiles: Number(parsed.totalFiles ?? 0),
    status: typeof parsed.status === 'string' ? parsed.status : '',
    statusCounts: parsed.statusCounts ?? {},
  }
}

async function poll(id: string) {
  try {
    const { data } = await api.get<Progress & { id: string }>(`/api/import-batches/${id}`)
    progress.value = {
      totalFiles: data.totalFiles,
      status: data.status,
      statusCounts: data.statusCounts ?? {},
    }
  } catch (error) {
    liveMessage.value = readApiError(error).message
  }
}

function listen(id: string) {
  stopListening()
  const source = new EventSource(`/api/import-batches/${id}/events`, { withCredentials: true })
  eventSource = source
  source.addEventListener('progress', (event) => {
    try {
      const next = readProgress((event as MessageEvent).data)
      if (next) {
        progress.value = next
      }
    } catch {
      liveMessage.value = '导入进度无法读取'
    }
  })
  source.addEventListener('error', () => {
    source.close()
    if (pollTimer !== undefined) {
      return
    }
    pollTimer = window.setInterval(() => {
      void poll(id)
    }, 2000)
  })
}

async function upload() {
  liveMessage.value = ''
  if (errors.value.length > 0 || files.value.length === 0) {
    return
  }
  busy.value = true
  try {
    const body = new FormData()
    for (const file of files.value) {
      body.append('files', file)
    }
    const { data } = await api.post<{ id: string; totalFiles: number; status: string }>('/api/import-batches', body)
    progress.value = {
      totalFiles: data.totalFiles,
      status: data.status,
      statusCounts: {},
    }
    listen(data.id)
  } catch (error) {
    liveMessage.value = readApiError(error).message
  } finally {
    busy.value = false
  }
}

onUnmounted(stopListening)
</script>

<template>
  <section class="card upload-card">
    <h2>导入票据</h2>
    <p class="lede">一次最多 100 个 JPG、JPEG、PNG 或 PDF，每个不超过 20 MiB。</p>
    <form @submit.prevent="upload">
      <div class="field">
        <label for="import-files">票据文件</label>
        <input
          id="import-files"
          name="files"
          type="file"
          multiple
          accept=".jpg,.jpeg,.png,.pdf,image/jpeg,image/png,application/pdf"
          @change="onInput"
        />
      </div>
      <div class="dropzone" data-dropzone @dragover.prevent @drop.prevent="onDrop">
        将 JPG、JPEG、PNG 或 PDF 拖到这里
      </div>
      <ul v-if="files.length" class="file-list">
        <li v-for="file in files" :key="file.name + file.size">{{ file.name }}</li>
      </ul>
      <ul v-if="errors.length" class="error-list">
        <li v-for="error in errors" :key="error" class="field-error">{{ error }}</li>
      </ul>
      <p class="live-error" aria-live="assertive">{{ liveMessage }}</p>
      <button class="button-primary" type="submit" data-action="upload" :disabled="busy">上传</button>
    </form>
    <BatchProgress
      v-if="progress"
      :total-files="progress.totalFiles"
      :status="progress.status"
      :status-counts="progress.statusCounts"
    />
  </section>
</template>
