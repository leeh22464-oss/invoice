import { flushPromises, mount } from '@vue/test-utils'
import { AxiosHeaders, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import { describe, expect, it } from 'vitest'

import { api } from '../../lib/api'
import ReviewWorkbench from './ReviewWorkbench.vue'

type Issue = {
  code: string
  field: string
  message: string
  severity: string
}

type Evidence = {
  matchType: string
  matchedInvoiceId: string
  score: number
  matchedFields: string[]
}

type Invoice = {
  id: string
  invoiceType: string
  invoiceCode: string | null
  invoiceNumber: string | null
  invoiceDate: string | null
  amount: string | null
  tax: string | null
  total: string | null
  status: string
  fields: Record<string, string>
  createdAt: string
  updatedAt: string
  fieldConfidence?: Record<string, number>
  issues?: Issue[]
  duplicateEvidence?: Evidence | null
}

function invoice(overrides: Partial<Invoice> = {}): Invoice {
  return {
    id: 'inv-1',
    invoiceType: 'VAT_SPECIAL',
    invoiceCode: '011001900111',
    invoiceNumber: '00000001',
    invoiceDate: '2026-01-02',
    amount: '100.00',
    tax: '13.00',
    total: '113.00',
    status: 'REVIEW_REQUIRED',
    fields: {
      invoiceNumber: '00000001',
      amount: '100.00',
    },
    createdAt: '2026-01-02T00:00:00Z',
    updatedAt: '2026-01-02T00:00:00Z',
    ...overrides,
  }
}

function jsonResponse(
  config: InternalAxiosRequestConfig,
  status: number,
  data: unknown,
  headers: Record<string, string> = {},
): AxiosResponse {
  return {
    data,
    status,
    statusText: String(status),
    headers: new AxiosHeaders(headers),
    config,
  }
}

function fileResponse(config: InternalAxiosRequestConfig, contentType: string, filename: string) {
  return jsonResponse(config, 200, new Blob(['file'], { type: contentType }), {
    'content-type': contentType,
    'content-disposition': `inline; filename="${filename}"`,
  })
}

function install(handler: (config: InternalAxiosRequestConfig) => AxiosResponse | Promise<AxiosResponse>) {
  const calls: InternalAxiosRequestConfig[] = []
  api.defaults.adapter = async (config) => {
    calls.push(config)
    return handler(config)
  }
  return calls
}

async function mountBench() {
  const wrapper = mount(ReviewWorkbench)
  await flushPromises()
  return wrapper
}

describe('ReviewWorkbench', () => {
  it('changes the preview and fields with ArrowDown and ArrowUp', async () => {
    const first = invoice({
      id: 'inv-1',
      invoiceNumber: '00000001',
      fields: { invoiceNumber: '00000001', amount: '100.00' },
    })
    const second = invoice({
      id: 'inv-2',
      invoiceNumber: '00000002',
      invoiceType: 'RAILWAY',
      fields: { invoiceNumber: '00000002', amount: '50.00' },
    })
    const calls = install((config) => {
      const url = config.url ?? ''
      if (url === '/api/invoices') {
        return jsonResponse(config, 200, [first, second])
      }
      if (url === '/api/invoices/inv-2/file') {
        return fileResponse(config, 'application/pdf', 'ride.pdf')
      }
      return fileResponse(config, 'image/jpeg', 'ticket.jpg')
    })

    const wrapper = await mountBench()

    expect(wrapper.get('.workbench').attributes('style') ?? '').not.toContain('background')
    expect((wrapper.get('input[name="amount"]').element as HTMLInputElement).value).toBe('100.00')
    expect(wrapper.get('[data-preview] img').attributes('src')).toMatch(/^blob:/)
    const firstFile = calls.find((call) => call.url === '/api/invoices/inv-1/file')
    expect(firstFile?.withCredentials).toBe(true)
    expect(wrapper.get('[data-preview] img').attributes('src')).not.toContain('/api/invoices/inv-1/file')

    await wrapper.get('[role="listbox"]').trigger('keydown', { key: 'ArrowDown' })
    await flushPromises()

    expect(wrapper.get('[aria-selected="true"]').text()).toContain('00000002')
    expect((wrapper.get('input[name="amount"]').element as HTMLInputElement).value).toBe('50.00')
    expect((wrapper.get('input[name="invoiceNumber"]').element as HTMLInputElement).value).toBe('00000002')
    expect(wrapper.get('[data-preview] a').attributes('href')).toBe('/api/invoices/inv-2/file')
    expect(wrapper.get('[data-preview] a').text()).toContain('ride.pdf')
    expect(wrapper.find('[data-preview] img').exists()).toBe(false)
    const secondFile = calls.find((call) => call.url === '/api/invoices/inv-2/file')
    expect(secondFile?.withCredentials).toBe(true)

    await wrapper.get('[role="listbox"]').trigger('keydown', { key: 'ArrowUp' })
    await flushPromises()

    expect((wrapper.get('input[name="invoiceNumber"]').element as HTMLInputElement).value).toBe('00000001')
    expect(wrapper.get('[data-preview] img').attributes('src')).toMatch(/^blob:/)
  })

  it('labels only fields whose confidence is below 0.80', async () => {
    install((config) => jsonResponse(config, 200, [
      invoice({
        fieldConfidence: {
          invoiceNumber: 0.8,
          amount: 0.79,
        },
      }),
    ]))

    const wrapper = await mountBench()

    expect(wrapper.get('[data-field="amount"]').text()).toContain('低置信度')
    expect(wrapper.get('[data-field="amount"] label').text()).toContain('金额')
    expect(wrapper.get('[data-field="invoiceNumber"]').text()).not.toContain('低置信度')
    expect(wrapper.get('label[for="field-amount"]').text()).toContain('金额')
    expect(wrapper.get('label[for="field-invoiceNumber"]').text()).toContain('发票号码')
  })

  it('disables approval for an exact duplicate and for BLOCKED_DUPLICATE', async () => {
    install((config) => jsonResponse(config, 200, [
      invoice({
        id: 'inv-exact',
        status: 'REVIEW_REQUIRED',
        duplicateEvidence: {
          matchType: 'EXACT',
          matchedInvoiceId: 'inv-existing',
          score: 1,
          matchedFields: ['invoiceNumber'],
        },
      }),
      invoice({
        id: 'inv-blocked',
        invoiceNumber: '00000009',
        status: 'BLOCKED_DUPLICATE',
        fields: { invoiceNumber: '00000009', amount: '9.00' },
        duplicateEvidence: {
          matchType: 'SUSPECTED',
          matchedInvoiceId: 'inv-soft',
          score: 0.4,
          matchedFields: ['amount'],
        },
      }),
    ]))

    const wrapper = await mountBench()
    expect(wrapper.get('[data-action="approve"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-duplicate]').text()).toContain('inv-existing')

    await wrapper.get('[role="listbox"]').trigger('keydown', { key: 'ArrowDown' })
    await flushPromises()

    expect(wrapper.get('[data-action="approve"]').attributes('disabled')).toBeDefined()
    expect(wrapper.findAll('.button-primary')).toHaveLength(1)
  })

  it('refreshes issues and duplicate evidence after saving a correction', async () => {
    const calls = install((config) => {
      const method = (config.method ?? 'get').toLowerCase()
      const url = config.url ?? ''
      if (method === 'get' && url === '/api/invoices') {
        return jsonResponse(config, 200, [
          invoice({
            issues: [{ code: 'AMOUNT_MISMATCH', field: 'amount', message: '金额不一致', severity: 'ERROR' }],
            duplicateEvidence: {
              matchType: 'EXACT',
              matchedInvoiceId: 'inv-existing',
              score: 1,
              matchedFields: ['invoiceNumber'],
            },
          }),
        ])
      }
      if (method === 'get' && url.endsWith('/file')) {
        return fileResponse(config, 'image/png', 'ticket.png')
      }
      if (method === 'patch') {
        return jsonResponse(config, 200, invoice({
          fields: { invoiceNumber: '00000001', amount: '80.00' },
          amount: '80.00',
          issues: [{ code: 'RECHECKED', field: 'amount', message: '已重新核验', severity: 'INFO' }],
          duplicateEvidence: {
            matchType: 'SUSPECTED',
            matchedInvoiceId: 'inv-other',
            score: 0.42,
            matchedFields: ['amount'],
          },
        }))
      }
      return jsonResponse(config, 200, invoice({ status: 'QUEUED' }))
    })

    const wrapper = await mountBench()
    expect(wrapper.get('[data-issues]').text()).toContain('金额不一致')
    expect(wrapper.get('[data-duplicate]').text()).toContain('inv-existing')
    expect(wrapper.get('[data-action="approve"]').attributes('disabled')).toBeDefined()

    await wrapper.get('input[name="amount"]').setValue('80.00')
    await wrapper.get('[data-action="save"]').trigger('click')
    await flushPromises()

    const patch = calls.find((call) => (call.method ?? '').toLowerCase() === 'patch')
    const body = typeof patch?.data === 'string' ? JSON.parse(patch.data) : patch?.data
    expect(patch?.url).toBe('/api/invoices/inv-1')
    expect(body).toEqual({
      fields: {
        invoiceNumber: '00000001',
        amount: '80.00',
      },
    })
    expect(wrapper.get('[data-issues]').text()).toContain('已重新核验')
    expect(wrapper.get('[data-issues]').text()).not.toContain('金额不一致')
    expect(wrapper.get('[data-duplicate]').text()).toContain('inv-other')
    expect(wrapper.get('[data-duplicate]').text()).toContain('0.42')
    expect(wrapper.get('[data-duplicate]').text()).not.toContain('inv-existing')
    expect(wrapper.get('[data-action="approve"]').attributes('disabled')).toBeUndefined()

    await wrapper.get('[data-action="approve"]').trigger('click')
    await flushPromises()
    const approval = calls.filter((call) => (call.method ?? '').toLowerCase() === 'patch').at(-1)
    const approvalBody = typeof approval?.data === 'string' ? JSON.parse(approval.data) : approval?.data
    expect(approvalBody).toEqual({ status: 'PASSED' })

    await wrapper.get('[data-action="retry"]').trigger('click')
    await flushPromises()
    const retry = calls.find((call) => (call.url ?? '').endsWith('/retry'))
    expect(retry?.url).toBe('/api/invoices/inv-1/retry')
    expect((retry?.method ?? '').toLowerCase()).toBe('post')
  })

  it('announces review API errors', async () => {
    api.defaults.adapter = async (config) => {
      throw Object.assign(new Error('fail'), {
        isAxiosError: true,
        response: { data: { code: 'ERROR', message: '复核队列读取失败' }, status: 500 },
      })
    }

    const wrapper = await mountBench()
    expect(wrapper.get('[aria-live="assertive"]').text()).toContain('复核队列读取失败')
  })
})
