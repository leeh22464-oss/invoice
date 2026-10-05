import { flushPromises, mount } from '@vue/test-utils'
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { createPinia } from 'pinia'
import { afterEach, describe, expect, it, vi } from 'vitest'

import AppShell from '../../components/AppShell.vue'
import { api } from '../../lib/api'
import { router } from '../../router'
import UploadPanel from './UploadPanel.vue'

const TYPE_ERROR = '仅支持 JPG、JPEG、PNG 和 PDF'
const SIZE_ERROR = '单个文件不能超过 20 MiB'
const COUNT_ERROR = '一次最多上传 100 个文件'
const MAX_BYTES = 20 * 1024 * 1024

class FakeEventSource {
  static latest: FakeEventSource | null = null

  url: string
  withCredentials: boolean
  closed = false
  private listeners = new Map<string, Array<(event: Event) => void>>()

  constructor(url: string, init?: EventSourceInit) {
    this.url = url
    this.withCredentials = Boolean(init?.withCredentials)
    FakeEventSource.latest = this
  }

  addEventListener(type: string, listener: (event: Event) => void) {
    const list = this.listeners.get(type) ?? []
    list.push(listener)
    this.listeners.set(type, list)
  }

  removeEventListener() {}

  close() {
    this.closed = true
  }

  emit(type: string, data?: string) {
    const event = type === 'error' ? new Event('error') : new MessageEvent(type, { data })
    for (const listener of this.listeners.get(type) ?? []) {
      listener(event)
    }
  }
}

function jsonResponse(
  config: InternalAxiosRequestConfig,
  status: number,
  data: unknown,
): AxiosResponse {
  return {
    data,
    status,
    statusText: String(status),
    headers: {},
    config,
  }
}

function fileOf(name: string, type: string, size = 32) {
  const file = new File([new Uint8Array(1)], name, { type })
  Object.defineProperty(file, 'size', { value: size })
  return file
}

function mountUpload() {
  return mount(UploadPanel)
}

function dropFiles(wrapper: ReturnType<typeof mountUpload>, files: File[]) {
  const event = new Event('drop', { bubbles: true, cancelable: true })
  Object.defineProperty(event, 'dataTransfer', { value: { files } })
  wrapper.get('[data-dropzone]').element.dispatchEvent(event)
}

function chooseFiles(wrapper: ReturnType<typeof mountUpload>, files: File[]) {
  const input = wrapper.get('input[type="file"]').element as HTMLInputElement
  Object.defineProperty(input, 'files', { configurable: true, value: files })
  input.dispatchEvent(new Event('change', { bubbles: true }))
}

describe('UploadPanel', () => {
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
    FakeEventSource.latest = null
    document.cookie = 'XSRF-TOKEN=; Max-Age=0'
  })

  it('uses the same type, size, and count validation for drop and the file input before any request', async () => {
    const calls: InternalAxiosRequestConfig[] = []
    api.defaults.adapter = async (config) => {
      calls.push(config)
      return jsonResponse(config, 201, { id: 'batch-1', totalFiles: 1, status: 'QUEUED' })
    }

    const wrapper = mountUpload()
    dropFiles(wrapper, [fileOf('notes.txt', 'text/plain')])
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain(TYPE_ERROR)
    expect(calls).toHaveLength(0)

    chooseFiles(wrapper, [fileOf('photo.jpg', 'text/plain')])
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain(TYPE_ERROR)
    expect(calls).toHaveLength(0)

    dropFiles(wrapper, [fileOf('scan.jpg', 'image/jpeg', MAX_BYTES + 1)])
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain(SIZE_ERROR)
    expect(wrapper.text()).not.toContain(TYPE_ERROR)
    expect(calls).toHaveLength(0)

    chooseFiles(wrapper, [fileOf('exact.jpg', 'image/jpeg', MAX_BYTES)])
    await flushPromises()
    expect(wrapper.text()).not.toContain(SIZE_ERROR)
    expect(calls).toHaveLength(0)

    const many = Array.from({ length: 101 }, (_, index) => fileOf(`page-${index}.png`, 'image/png'))
    chooseFiles(wrapper, many)
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain(COUNT_ERROR)
    expect(calls).toHaveLength(0)

    dropFiles(wrapper, Array.from({ length: 100 }, (_, index) => fileOf(`ok-${index}.pdf`, 'application/pdf')))
    await flushPromises()
    expect(wrapper.text()).not.toContain(COUNT_ERROR)
    expect(calls).toHaveLength(0)
  })

  it('posts files through the api client and polls the batch when SSE fails', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    vi.stubGlobal('EventSource', FakeEventSource)
    document.cookie = 'XSRF-TOKEN=raw-csrf-token'

    const calls: InternalAxiosRequestConfig[] = []
    api.defaults.adapter = async (config) => {
      calls.push(config)
      const method = (config.method ?? 'get').toLowerCase()
      if (method === 'post') {
        return jsonResponse(config, 201, { id: 'batch-1', totalFiles: 1, status: 'QUEUED' })
      }
      return jsonResponse(config, 200, {
        id: 'batch-1',
        totalFiles: 1,
        status: 'COMPLETED',
        statusCounts: { PASSED: 1 },
      })
    }

    const wrapper = mountUpload()
    chooseFiles(wrapper, [fileOf('scan.jpeg', 'image/jpeg')])
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    const post = calls.find((call) => (call.method ?? '').toLowerCase() === 'post')
    expect(post?.url).toBe('/api/import-batches')
    expect(post?.withCredentials).toBe(true)
    expect(post?.headers.get('X-XSRF-TOKEN')).toBe('raw-csrf-token')
    expect(post?.data).toBeInstanceOf(FormData)
    const posted = (post?.data as FormData).getAll('files')
    expect(posted).toHaveLength(1)
    expect((posted[0] as File).name).toBe('scan.jpeg')

    const source = FakeEventSource.latest
    expect(source?.url).toBe('/api/import-batches/batch-1/events')
    expect(source?.withCredentials).toBe(true)

    source?.emit('progress', JSON.stringify({
      totalFiles: 1,
      status: 'QUEUED',
      statusCounts: { QUEUED: 1 },
    }))
    await flushPromises()

    expect(wrapper.get('[aria-label="导入进度"]').text()).toContain('排队中')
    expect(wrapper.get('[aria-label="导入进度"]').text()).toContain('1')
    expect(wrapper.text()).not.toContain('secretKey')
    expect(wrapper.text()).not.toContain('{')

    source?.emit('error')
    await vi.advanceTimersByTimeAsync(1999)
    await flushPromises()
    expect(calls.filter((call) => (call.method ?? '').toLowerCase() === 'get')).toHaveLength(0)

    await vi.advanceTimersByTimeAsync(1)
    await flushPromises()
    const firstPoll = calls.filter((call) => (call.method ?? '').toLowerCase() === 'get')
    expect(firstPoll).toHaveLength(1)
    expect(firstPoll[0]?.url).toBe('/api/import-batches/batch-1')
    expect(wrapper.text()).toContain('已完成')
    expect(wrapper.get('[aria-label="导入进度"]').text()).toContain('已通过')
    expect(wrapper.get('[aria-label="导入进度"]').text()).toContain('1')

    await vi.advanceTimersByTimeAsync(2000)
    await flushPromises()
    expect(calls.filter((call) => (call.method ?? '').toLowerCase() === 'get')).toHaveLength(1)
  })

  it('ignores a stale batch poll after a newer upload starts', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    vi.stubGlobal('EventSource', FakeEventSource)

    let releaseFirst: ((response: AxiosResponse) => void) | undefined
    let posts = 0
    api.defaults.adapter = async (config) => {
      const method = (config.method ?? 'get').toLowerCase()
      if (method === 'post') {
        posts += 1
        const id = posts === 1 ? 'batch-1' : 'batch-2'
        return jsonResponse(config, 201, { id, totalFiles: 1, status: 'QUEUED' })
      }
      if ((config.url ?? '').endsWith('batch-1')) {
        return new Promise<AxiosResponse>((resolve) => {
          releaseFirst = () => resolve(jsonResponse(config, 200, {
            id: 'batch-1',
            totalFiles: 1,
            status: 'COMPLETED',
            statusCounts: { PASSED: 1 },
          }))
        })
      }
      return jsonResponse(config, 200, {
        id: 'batch-2',
        totalFiles: 1,
        status: 'PROCESSING',
        statusCounts: { OCR_PROCESSING: 1 },
      })
    }

    const wrapper = mountUpload()
    chooseFiles(wrapper, [fileOf('scan.jpeg', 'image/jpeg')])
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    FakeEventSource.latest?.emit('error')
    await vi.advanceTimersByTimeAsync(2000)

    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('[aria-label="导入进度"]').text()).toContain('排队中')

    releaseFirst?.()
    await flushPromises()
    expect(wrapper.get('[aria-label="导入进度"]').text()).toContain('排队中')
    expect(wrapper.get('[aria-label="导入进度"]').text()).not.toContain('已完成')
  })

  it('keeps upload, review, and ledger reachable from the shell', () => {
    const paths = router.getRoutes().map((route) => route.path)
    expect(paths).toContain('/imports')
    expect(paths).toContain('/review')
    expect(paths).toContain('/ledger')
    expect(paths).toContain('/settings/ocr')

    const wrapper = mount(AppShell, {
      global: {
        plugins: [createPinia(), router],
      },
    })

    const links = wrapper.findAll('a').map((link) => ({
      text: link.text(),
      href: link.attributes('href'),
    }))
    expect(links).toEqual(expect.arrayContaining([
      { text: '导入', href: '/imports' },
      { text: '复核', href: '/review' },
      { text: '台账', href: '/ledger' },
      { text: '凭证', href: '/settings/ocr' },
    ]))
    expect(wrapper.findAll('.button-primary')).toHaveLength(0)
  })
})
