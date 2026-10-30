import { flushPromises, mount } from '@vue/test-utils'
import axios, { type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import { createPinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'

import { api } from '../../lib/api'
import LoginView from './LoginView.vue'

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

async function mountLogin() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', component: LoginView },
      { path: '/register', component: { template: '<div>注册</div>' } },
      { path: '/review', component: { template: '<div>复核</div>' } },
      { path: '/settings/ocr', component: { template: '<div>凭证设置</div>' } },
    ],
  })
  await router.push('/login')
  await router.isReady()

  const wrapper = mount(LoginView, {
    global: {
      plugins: [createPinia(), router],
    },
  })

  return { wrapper, router }
}

describe('LoginView', () => {
  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=raw-csrf-token'
  })

  it('sends the CSRF header and email and password', async () => {
    const calls: InternalAxiosRequestConfig[] = []
    api.defaults.adapter = async (config) => {
      calls.push(config)
      const url = config.url ?? ''
      if (url.includes('/api/settings/ocr-credentials')) {
        return jsonResponse(config, 200, {
          configured: false,
          apiKeyMask: null,
          validationStatus: null,
          lastValidatedAt: null,
          updatedAt: null,
        })
      }
      return jsonResponse(config, 200, {
        id: '11111111-1111-1111-1111-111111111111',
        email: 'user@example.com',
      })
    }

    const { wrapper } = await mountLogin()
    await wrapper.get('input[name="email"]').setValue('user@example.com')
    await wrapper.get('input[name="password"]').setValue('Correct-Horse-1')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    const login = calls.find((call) => (call.url ?? '').includes('/api/auth/login'))
    expect(login).toBeTruthy()
    expect(login?.withCredentials).toBe(true)
    const body = typeof login?.data === 'string' ? JSON.parse(login.data) : login?.data
    expect(body).toEqual({
      email: 'user@example.com',
      password: 'Correct-Horse-1',
    })
    expect(login?.headers.get('X-XSRF-TOKEN')).toBe('raw-csrf-token')
  })

  it('announces the API message when login fails', async () => {
    api.defaults.adapter = async (config) => {
      throw new axios.AxiosError(
        'Request failed with status code 401',
        'ERR_BAD_REQUEST',
        config,
        null,
        jsonResponse(config, 401, {
          code: 'INVALID_CREDENTIALS',
          message: 'Invalid email or password',
          errors: { password: 'Invalid email or password' },
        }),
      )
    }

    const { wrapper } = await mountLogin()
    await wrapper.get('input[name="email"]').setValue('user@example.com')
    await wrapper.get('input[name="password"]').setValue('wrong-password')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.get('[aria-live="assertive"]').text()).toContain('Invalid email or password')
    expect(wrapper.text()).toContain('Invalid email or password')
  })
})
