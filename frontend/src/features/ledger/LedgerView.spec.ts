import { flushPromises, mount } from '@vue/test-utils'
import axios, { type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import { describe, expect, it } from 'vitest'

import { api } from '../../lib/api'
import LedgerView from './LedgerView.vue'

type Row = {
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
}

function row(overrides: Partial<Row> = {}): Row {
  return {
    id: 'led-1',
    invoiceType: 'VAT_SPECIAL',
    invoiceCode: '011001900111',
    invoiceNumber: 'KEEP-1',
    invoiceDate: '2026-01-02',
    amount: '100.00',
    tax: '13.00',
    total: '113.00',
    status: 'PASSED',
    fields: {},
    createdAt: '2026-01-02T00:00:00Z',
    updatedAt: '2026-01-02T00:00:00Z',
    ...overrides,
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

describe('LedgerView', () => {
  it('shows only accepted rows and maps filters onto ledger and export queries', async () => {
    const calls: InternalAxiosRequestConfig[] = []
    api.defaults.adapter = async (config) => {
      calls.push(config)
      return jsonResponse(config, 200, [
        row(),
        row({ id: 'led-2', invoiceNumber: 'HIDE-2', status: 'BLOCKED_DUPLICATE' }),
        row({ id: 'led-3', invoiceNumber: 'HIDE-3', status: 'REVIEW_REQUIRED' }),
      ])
    }

    const wrapper = mount(LedgerView)
    await flushPromises()

    expect(wrapper.get('[data-state="ready"]').text()).toContain('KEEP-1')
    expect(wrapper.text()).not.toContain('HIDE-2')
    expect(wrapper.text()).not.toContain('HIDE-3')
    expect(wrapper.get('a[data-export="csv"]').attributes('href')).toBe('/api/ledger/export.csv')
    expect(wrapper.get('a[data-export="xlsx"]').attributes('href')).toBe('/api/ledger/export.xlsx')
    expect(wrapper.findAll('.button-primary')).toHaveLength(0)

    await wrapper.get('input[name="invoiceNumber"]').setValue('00000008')
    await flushPromises()

    expect(calls.at(-1)?.params).toEqual({ invoiceNumber: '00000008' })
    expect(wrapper.get('a[data-export="csv"]').attributes('href')).toBe('/api/ledger/export.csv?invoiceNumber=00000008')
    expect(wrapper.get('a[data-export="xlsx"]').attributes('href')).toBe('/api/ledger/export.xlsx?invoiceNumber=00000008')

    await wrapper.get('select[name="invoiceType"]').setValue('VAT_SPECIAL')
    await wrapper.get('input[name="dateFrom"]').setValue('2026-01-01')
    await wrapper.get('input[name="dateTo"]').setValue('2026-01-31')
    await flushPromises()

    expect(calls.at(-1)?.url).toBe('/api/ledger')
    expect(calls.at(-1)?.params).toEqual({
      invoiceType: 'VAT_SPECIAL',
      invoiceNumber: '00000008',
      dateFrom: '2026-01-01',
      dateTo: '2026-01-31',
    })
    expect(wrapper.get('a[data-export="csv"]').attributes('href')).toBe(
      '/api/ledger/export.csv?invoiceType=VAT_SPECIAL&invoiceNumber=00000008&dateFrom=2026-01-01&dateTo=2026-01-31',
    )
    expect(wrapper.get('a[data-export="xlsx"]').attributes('href')).toBe(
      '/api/ledger/export.xlsx?invoiceType=VAT_SPECIAL&invoiceNumber=00000008&dateFrom=2026-01-01&dateTo=2026-01-31',
    )
    expect(wrapper.get('label[for="filter-type"]').text()).toContain('发票类型')
    expect(wrapper.get('label[for="filter-number"]').text()).toContain('发票号码')
    expect(wrapper.get('label[for="filter-from"]').text()).toContain('开始日期')
    expect(wrapper.get('label[for="filter-to"]').text()).toContain('结束日期')
  })

  it('distinguishes loading, empty, and error states', async () => {
    let release: ((response: AxiosResponse) => void) | undefined
    api.defaults.adapter = (config) => new Promise((resolve) => {
      release = (response) => resolve(response)
      void config
    })

    const loading = mount(LedgerView)
    await flushPromises()
    expect(loading.get('[data-state="loading"]').text()).toContain('正在加载台账')
    expect(loading.find('[data-state="empty"]').exists()).toBe(false)
    expect(loading.find('[data-state="error"]').exists()).toBe(false)
    expect(loading.text()).not.toContain('暂无已通过票据')

    release?.(jsonResponse({} as InternalAxiosRequestConfig, 200, []))
    await flushPromises()

    expect(loading.get('[data-state="empty"]').text()).toContain('暂无已通过票据')
    expect(loading.find('[data-state="loading"]').exists()).toBe(false)
    expect(loading.find('[data-state="error"]').exists()).toBe(false)
    expect(loading.text()).not.toContain('正在加载台账')

    api.defaults.adapter = async (config) => {
      throw new axios.AxiosError(
        'Request failed',
        'ERR_BAD_RESPONSE',
        config,
        null,
        jsonResponse(config, 503, { code: 'UNAVAILABLE', message: '台账读取失败' }),
      )
    }

    const failed = mount(LedgerView)
    await flushPromises()

    expect(failed.get('[data-state="error"]').text()).toContain('台账读取失败')
    expect(failed.get('[aria-live="assertive"]').text()).toContain('台账读取失败')
    expect(failed.find('[data-state="loading"]').exists()).toBe(false)
    expect(failed.find('[data-state="empty"]').exists()).toBe(false)
    expect(failed.text()).not.toContain('正在加载台账')
    expect(failed.text()).not.toContain('暂无已通过票据')
  })

  it('keeps the newer ledger rows when an older filter response arrives later', async () => {
    const pending: Array<(response: AxiosResponse) => void> = []
    api.defaults.adapter = () => new Promise((resolve) => {
      pending.push((response) => resolve(response))
    })

    const wrapper = mount(LedgerView)
    await flushPromises()
    await wrapper.get('input[name="invoiceNumber"]').setValue('old')
    await flushPromises()
    await wrapper.get('input[name="invoiceNumber"]').setValue('new')
    await flushPromises()

    expect(pending).toHaveLength(3)
    const older = pending.length - 2
    const newer = pending.length - 1
    pending[newer](jsonResponse({} as InternalAxiosRequestConfig, 200, [
      row({ id: 'led-new', invoiceNumber: 'KEEP-NEW' }),
    ]))
    await flushPromises()
    pending[older](jsonResponse({} as InternalAxiosRequestConfig, 200, [
      row({ id: 'led-old', invoiceNumber: 'STALE-OLD' }),
    ]))
    await flushPromises()

    expect(wrapper.get('[data-state="ready"]').text()).toContain('KEEP-NEW')
    expect(wrapper.text()).not.toContain('STALE-OLD')
  })
})
