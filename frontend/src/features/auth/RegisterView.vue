<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'

import { readApiError } from '../../lib/api'
import { useSessionStore } from '../../stores/session'

const passwordRule = /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[^A-Za-z0-9]).{12,128}$/
const passwordRuleText = '密码须为 12–128 个字符，并包含大写字母、小写字母、数字和符号。'

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
  if (!passwordRule.test(password.value)) {
    fieldErrors.value = { password: passwordRuleText }
    liveMessage.value = passwordRuleText
    return
  }

  submitting.value = true
  try {
    await session.register(email.value, password.value)
    await router.push('/settings/ocr')
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
    <h2>注册</h2>
    <p class="lede">创建账号后即可配置 OCR 凭证。</p>
    <form @submit.prevent="onSubmit">
      <div class="field">
        <label for="register-email">邮箱</label>
        <input
          id="register-email"
          name="email"
          type="email"
          autocomplete="email"
          required
          v-model="email"
          :aria-describedby="fieldErrors.email ? 'register-email-error' : undefined"
        />
        <p v-if="fieldErrors.email" id="register-email-error" class="field-error">{{ fieldErrors.email }}</p>
      </div>
      <div class="field">
        <label for="register-password">密码</label>
        <input
          id="register-password"
          name="password"
          type="password"
          autocomplete="new-password"
          required
          minlength="12"
          maxlength="128"
          v-model="password"
          aria-describedby="password-rule"
        />
        <p id="password-rule" class="rule">{{ passwordRuleText }}</p>
        <p v-if="fieldErrors.password" id="register-password-error" class="field-error">{{ fieldErrors.password }}</p>
      </div>
      <p class="live-error" aria-live="assertive">{{ liveMessage }}</p>
      <button class="button-primary" type="submit" :disabled="submitting">注册</button>
    </form>
    <p class="switch-auth">已有账号？<router-link to="/login">登录</router-link></p>
  </section>
</template>
