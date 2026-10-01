<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'

import { readApiError } from '../lib/api'
import { useSessionStore } from '../stores/session'

const router = useRouter()
const session = useSessionStore()
const liveMessage = ref('')
const submitting = ref(false)

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
    <div class="shell-bar">
      <p class="shell-user">{{ session.user?.email }}</p>
      <button class="button-quiet" type="button" :disabled="submitting" @click="logout">退出登录</button>
    </div>
    <p class="live-error" aria-live="assertive">{{ liveMessage }}</p>
    <router-view />
  </div>
</template>
