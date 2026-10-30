import { defineStore } from 'pinia'
import { ref } from 'vue'

import { api } from '../lib/api'

export type SessionUser = {
  id: string
  email: string
}

export const useSessionStore = defineStore('session', () => {
  const user = ref<SessionUser | null>(null)
  const ready = ref(false)
  let pendingRestore: Promise<void> | null = null

  function restore() {
    if (pendingRestore) {
      return pendingRestore
    }
    pendingRestore = (async () => {
      try {
        const { data } = await api.get<SessionUser>('/api/auth/me')
        user.value = data
      } catch {
        user.value = null
      } finally {
        ready.value = true
        pendingRestore = null
      }
    })()
    return pendingRestore
  }

  async function login(email: string, password: string) {
    const { data } = await api.post<SessionUser>('/api/auth/login', { email, password })
    user.value = data
    ready.value = true
    return data
  }

  async function register(email: string, password: string) {
    const { data } = await api.post<SessionUser>('/api/auth/register', { email, password })
    user.value = data
    ready.value = true
    return data
  }

  async function logout() {
    await api.post('/api/auth/logout')
    user.value = null
  }

  return { user, ready, restore, login, register, logout }
})
