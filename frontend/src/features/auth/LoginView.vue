<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'

import { readApiError } from '../../lib/api'
import { useSessionStore } from '../../stores/session'

const router = useRouter()
const session = useSessionStore()

const email = ref('')
const password = ref('')
const liveMessage = ref('')
const fieldErrors = ref<Record<string, string>>({})
const submitting = ref(false)

async function onSubmit() {
  liveMessage.value = ''
  fieldErrors.value = {}
  submitting.value = true
  try {
    await session.login(email.value, password.value)
    await router.push('/review')
  } catch (error) {
    const parsed = readApiError(error)
    liveMessage.value = parsed.message
    fieldErrors.value = parsed.errors ?? {}
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <section class="card auth-card">
    <p class="brand-mark">智能核票</p>
    <h2>登录</h2>
    <p class="lede">使用邮箱进入核票工作区。</p>
    <form @submit.prevent="onSubmit">
      <div class="field">
        <label for="login-email">邮箱</label>
        <input
          id="login-email"
          name="email"
          type="email"
          autocomplete="email"
          required
          v-model="email"
          :aria-describedby="fieldErrors.email ? 'login-email-error' : undefined"
        />
        <p v-if="fieldErrors.email" id="login-email-error" class="field-error">{{ fieldErrors.email }}</p>
      </div>
      <div class="field">
        <label for="login-password">密码</label>
        <input
          id="login-password"
          name="password"
          type="password"
          autocomplete="current-password"
          required
          v-model="password"
          :aria-describedby="fieldErrors.password ? 'login-password-error' : undefined"
        />
        <p v-if="fieldErrors.password" id="login-password-error" class="field-error">{{ fieldErrors.password }}</p>
      </div>
      <p class="live-error" aria-live="assertive">{{ liveMessage }}</p>
      <button class="button-primary" type="submit" :disabled="submitting">登录</button>
    </form>
    <p class="switch-auth">还没有账号？</p>
    <router-link class="button-quiet" to="/register">注册</router-link>
  </section>
</template>
