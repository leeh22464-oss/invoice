<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'

import { readApiError } from '../lib/api'
import { useSessionStore } from '../stores/session'

const router = useRouter()
const session = useSessionStore()
const liveMessage = ref('')
const submitting = ref(false)

const today = new Intl.DateTimeFormat('zh-CN', {
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
}).format(new Date())

const initial = computed(() => {
  const email = session.user?.email ?? ''
  return email.slice(0, 1).toUpperCase() || '票'
})

async function logout() {
  liveMessage.value = ''
  submitting.value = true
  try {
    await session.logout()
    await router.push('/login')
  } catch (error) {
    liveMessage.value = readApiError(error).message
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="shell">
    <header class="app-bar">
      <div class="app-brand-group">
        <router-link class="brand-badge" to="/review" aria-label="智能核票">票</router-link>
        <router-link class="app-brand" to="/review">智能核票</router-link>
        <nav class="app-nav" aria-label="工作区">
          <router-link to="/review">复核</router-link>
          <router-link to="/ledger">台账</router-link>
          <router-link to="/settings/ocr">凭证</router-link>
        </nav>
      </div>
      <div class="app-account">
        <span class="batch-label">{{ today }}</span>
        <router-link class="button-primary header-add" to="/imports">添加发票</router-link>
        <button
          class="avatar"
          type="button"
          :disabled="submitting"
          :title="session.user?.email"
          aria-label="退出登录"
          @click="logout"
        >
          {{ initial }}
        </button>
      </div>
    </header>
    <div class="shell-main">
      <p class="live-error" aria-live="assertive">{{ liveMessage }}</p>
      <router-view />
    </div>
  </div>
</template>
