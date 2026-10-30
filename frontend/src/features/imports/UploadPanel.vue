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

const TYPE_ALIASES: Record<string, string> = {
  'image/jpg': 'image/jpeg',
  'image/pjpeg': 'image/jpeg',
  'image/x-png': 'image/png',
  'application/x-pdf': 'application/pdf',
  'application/acrobat': 'application/pdf',
  'application/vnd.pdf': 'application/pdf',
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
const dragDepth = ref(0)

let eventSource: EventSource | null = null
let pollTimer: number | undefined
let activeBatchId = ''
let sawTerminal = false

function isTerminal(status: string): boolean {
  return status === 'COMPLETED' || status === 'FAILED'
}

function extensionOf(name: string): string {
  const dot = name.lastIndexOf('.')
  return dot < 0 ? '' : name.slice(dot + 1).toLowerCase()
}

function canonicalType(file: File): string {
  const expected = EXTENSION_TYPES[extensionOf(file.name)]
  if (!expected) {
    return ''
  }
  const declared = file.type.toLowerCase()
  if (!declared || declared === 'application/octet-stream' || declared === expected) {
    return expected
  }
  return TYPE_ALIASES[declared] === expected ? expected : ''
}

function accepted(file: File): boolean {
  return canonicalType(file) !== ''
}

function uploadFile(file: File): File {
  const type = canonicalType(file)
  if (!type || file.type.toLowerCase() === type) {
    return file
  }
  return new File([file], file.name, { type, lastModified: file.lastModified })
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

function allowDrop(event: DragEvent) {
  event.preventDefault()
  if (event.dataTransfer) {
    event.dataTransfer.dropEffect = 'copy'
  }
}

function onDragEnter(event: DragEvent) {
  allowDrop(event)
  dragDepth.value += 1
}

function onDragLeave() {
  dragDepth.value = Math.max(0, dragDepth.value - 1)
}

function onDrop(event: DragEvent) {
  event.preventDefault()
  dragDepth.value = 0
  void collectDroppedFiles(event).then(applyFiles)
}

function collectDroppedFiles(event: DragEvent): Promise<File[]> {
  const items = event.dataTransfer?.items
  if (!items || items.length === 0) {
    return Promise.resolve(Array.from(event.dataTransfer?.files ?? []))
  }
  const collected: File[] = []
  const tasks: Promise<void>[] = []
  for (const item of Array.from(items)) {
    const entry = item.webkitGetAsEntry?.()
    if (entry) {
      tasks.push(readEntry(entry, collected))
      continue
    }
    const file = item.getAsFile()
    if (file) {
      collected.push(file)
    }
  }
  if (tasks.length === 0 && collected.length === 0) {
    return Promise.resolve(Array.from(event.dataTransfer?.files ?? []))
  }
  return Promise.all(tasks).then(() => collected)
}

function readEntry(entry: FileSystemEntry, into: File[]): Promise<void> {
  if (entry.isFile) {
    return new Promise((resolve) => {
      (entry as FileSystemFileEntry).file((file) => {
        into.push(file)
        resolve()
      }, () => resolve())
    })
  }
  if (!entry.isDirectory) {
    return Promise.resolve()
  }
  const reader = (entry as FileSystemDirectoryEntry).createReader()
  return new Promise((resolve) => {
    const children: FileSystemEntry[] = []
    const readBatch = () => {
      reader.readEntries((entries) => {
        if (entries.length === 0) {
          void Promise.all(children.map((child) => readEntry(child, into))).then(() => resolve())
          return
        }
        children.push(...entries)
        readBatch()
      }, () => resolve())
    }
    readBatch()
  })
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

function applyProgress(id: string, next: Progress) {
  if (id !== activeBatchId) {
    return
  }
  progress.value = next
  if (isTerminal(next.status)) {
    sawTerminal = true
    if (pollTimer !== undefined) {
      window.clearInterval(pollTimer)
      pollTimer = undefined
    }
  }
}

async function poll(id: string) {
  try {
    const { data } = await api.get<Progress & { id: string }>(`/api/import-batches/${id}`)
    applyProgress(id, {
      totalFiles: data.totalFiles,
      status: data.status,
      statusCounts: data.statusCounts ?? {},
    })
  } catch (error) {
    if (id !== activeBatchId) {
      return
    }
    liveMessage.value = readApiError(error).message
  }
}

function listen(id: string) {
  stopListening()
  activeBatchId = id
  sawTerminal = false
  const source = new EventSource(`/api/import-batches/${id}/events`, { withCredentials: true })
  eventSource = source
  source.addEventListener('progress', (event) => {
    try {
      const next = readProgress((event as MessageEvent).data)
      if (next) {
        applyProgress(id, next)
      }
    } catch {
      if (id === activeBatchId) {
        liveMessage.value = '导入进度无法读取'
      }
    }
  })
  source.addEventListener('error', () => {
    source.close()
    if (id !== activeBatchId || sawTerminal || pollTimer !== undefined) {
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
      body.append('files', uploadFile(file))
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
  <section>
    <header class="desk-head">
      <div>
        <p class="eyebrow">添加发票</p>
        <h2>导入票据</h2>
      </div>
    </header>
    <div class="card upload-card">
    <p class="lede">可以一次选中多个文件，或把一组文件、整个文件夹拖到这里。一次最多 100 个 JPG、JPEG、PNG 或 PDF，每个不超过 20 MiB。</p>
    <form @submit.prevent="upload">
      <div
        class="dropzone"
        :class="{ 'is-dragover': dragDepth > 0 }"
        data-dropzone
        @dragenter.capture="onDragEnter"
        @dragover.capture="allowDrop"
        @dragleave="onDragLeave"
        @drop.capture="onDrop"
      >
        <p>将 JPG、JPEG、PNG 或 PDF 拖到这里</p>
        <div class="file-picker">
          <input
            id="import-files"
            class="file-picker-input"
            name="files"
            type="file"
            multiple
            accept=".jpg,.jpeg,.png,.pdf,image/jpeg,image/png,application/pdf"
            @change="onInput"
          />
          <label class="button-quiet file-picker-button" for="import-files">选择文件</label>
          <span class="muted">{{ files.length ? `已选择 ${files.length} 个文件` : '尚未选择文件' }}</span>
        </div>
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
    </div>
  </section>
</template>
