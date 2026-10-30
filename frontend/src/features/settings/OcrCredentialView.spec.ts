import { flushPromises, mount } from '@vue/test-utils'
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { createPinia } from 'pinia'
import { describe, expect, it } from 'vitest'

import { api } from '../../lib/api'
import OcrCredentialView from './OcrCredentialView.vue'

const rawSecret = 'super-secret-value'
const rawApiKey = 'raw-api-key-value'
const apiKeyMask = 'pk_live_****1234'
const lastValidatedAt = '2026-09-28T08:30:00.000Z'
const updatedAt = '2026-01-02T03:04:05.000Z'

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

function configuredPayload(validatedAt = lastValidatedAt) {
  return {
    configured: true,
    apiKeyMask,
    validationStatus: 'VALID',
    lastValidatedAt: validatedAt,
    updatedAt,
    apiKey: rawApiKey,
    secretKey: rawSecret,
  }
}

function mountCredentials() {
  return mount(OcrCredentialView, {
    global: {
      plugins: [createPinia()],
    },
  })
}

function secretValue(wrapper: ReturnType<typeof mountCredentials>) {
  return (wrapper.get('input[name="secretKey"]').element as HTMLInputElement).value
}

describe('OcrCredentialView', () => {
  it('never repopulates the Secret Key after load, save, and test', async () => {
    document.cookie = 'XSRF-TOKEN=raw-csrf-token'
    const calls: InternalAxiosRequestConfig[] = []

    api.defaults.adapter = async (config) => {
      calls.push(config)
      const url = config.url ?? ''
      const method = (config.method ?? 'get').toLowerCase()
      if (method === 'get') {
        return jsonResponse(config, 200, configuredPayload())
      }
      if (method === 'put') {
        return jsonResponse(config, 200, configuredPayload())
      }
      if (method === 'post' && url.includes('/test')) {
        return jsonResponse(config, 200, configuredPayload('2026-09-28T09:45:00.000Z'))
      }
      return jsonResponse(config, 200, configuredPayload())
    }

    const wrapper = mountCredentials()
    await flushPromises()

    expect(secretValue(wrapper)).toBe('')
    expect(secretValue(wrapper)).not.toBe(rawSecret)
    expect(secretValue(wrapper)).not.toBe(apiKeyMask)

    await wrapper.get('input[name="apiKey"]').setValue('entered-api-key')
    await wrapper.get('input[name="secretKey"]').setValue('entered-secret')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    const save = calls.find((call) => (call.method ?? '').toLowerCase() === 'put')
    const saveBody = typeof save?.data === 'string' ? JSON.parse(save.data) : save?.data
    expect(saveBody).toEqual({
      apiKey: 'entered-api-key',
      secretKey: 'entered-secret',
    })
    expect(secretValue(wrapper)).toBe('')
    expect(wrapper.text()).not.toContain(rawSecret)
    expect(wrapper.text()).not.toContain('entered-secret')

    await wrapper.get('[data-action="test"]').trigger('click')
    await flushPromises()

    expect(secretValue(wrapper)).toBe('')
    expect(wrapper.text()).not.toContain(rawSecret)
    expect(wrapper.get('input[name="apiKey"]').element).toHaveProperty(
      'value',
      'https://aistudio.baidu.com/llm/lmapi/v3',
    )
  })

  it('shows only the mask and validation time for a configured credential', async () => {
    api.defaults.adapter = async (config) => jsonResponse(config, 200, configuredPayload())

    const wrapper = mountCredentials()
    await flushPromises()

    expect(wrapper.text()).toContain(apiKeyMask)
    expect(wrapper.text()).toContain('VALID')
    expect(wrapper.get('time').attributes('datetime')).toBe(lastValidatedAt)
    expect(wrapper.get('time').text()).toContain('2026-09-28 08:30 UTC')
    expect(wrapper.text()).not.toContain(rawSecret)
    expect(wrapper.text()).not.toContain(rawApiKey)
    expect(wrapper.text()).not.toContain(updatedAt)
    expect(secretValue(wrapper)).toBe('')
    expect((wrapper.get('input[name="apiKey"]').element as HTMLInputElement).value).toBe(
      'https://aistudio.baidu.com/llm/lmapi/v3',
    )
  })
})
