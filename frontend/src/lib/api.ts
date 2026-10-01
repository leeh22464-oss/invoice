import axios from 'axios'

export const api = axios.create({
  withCredentials: true,
  xsrfCookieName: 'XSRF-TOKEN',
  xsrfHeaderName: 'X-XSRF-TOKEN',
  withXSRFToken: true,
})

function readCookie(name: string): string | null {
  if (typeof document === 'undefined') {
    return null
  }
  const cookies = document.cookie.split(';')
  for (const part of cookies) {
    const cookie = part.trim()
    const separator = cookie.indexOf('=')
    if (separator !== -1 && cookie.slice(0, separator) === name) {
      const value = cookie.slice(separator + 1)
      try {
        return decodeURIComponent(value)
      } catch {
        return value
      }
    }
  }
  return null
}

api.interceptors.request.use((config) => {
  const method = (config.method ?? 'get').toLowerCase()
  const mutating = method !== 'get' && method !== 'head' && method !== 'options'
  if (!mutating) {
    return config
  }
  const token = readCookie('XSRF-TOKEN')
  if (token) {
    config.headers.set('X-XSRF-TOKEN', token)
  }
  return config
})

export type ApiErrorBody = {
  code: string
  message: string
  errors?: Record<string, string>
}

export function readApiError(error: unknown): ApiErrorBody {
  if (axios.isAxiosError(error)) {
    const data = error.response?.data
    if (data && typeof data === 'object') {
      const body = data as Partial<ApiErrorBody>
      return {
        code: typeof body.code === 'string' ? body.code : 'ERROR',
        message: typeof body.message === 'string' ? body.message : '请求失败',
        errors: body.errors,
      }
    }
  }
  return { code: 'ERROR', message: '请求失败' }
}
