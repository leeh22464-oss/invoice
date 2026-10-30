<script setup lang="ts">
import { onMounted, ref } from 'vue'

import { api, readApiError } from '../../lib/api'

type CredentialStatus = {
  configured: boolean
  apiKeyMask: string | null
  validationStatus: string | null
  lastValidatedAt: string | null
  updatedAt: string | null
}

const serviceBase = 'https://aistudio.baidu.com/llm/lmapi/v3'

const status = ref<CredentialStatus | null>(null)
const apiKey = ref(serviceBase)
const secretKey = ref('')
const liveMessage = ref('')
const fieldErrors = ref<Record<string, string>>({})
const busy = ref(false)

function formatValidatedAt(value: string | null): string {
  if (!value) {
    return '尚未验证'
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }
  const pad = (part: number) => String(part).padStart(2, '0')
  return `${date.getUTCFullYear()}-${pad(date.getUTCMonth() + 1)}-${pad(date.getUTCDate())} ${pad(date.getUTCHours())}:${pad(date.getUTCMinutes())} UTC`
}

function applyStatus(data: CredentialStatus) {
  status.value = {
    configured: Boolean(data.configured),
    apiKeyMask: data.apiKeyMask ?? null,
    validationStatus: data.validationStatus ?? null,
    lastValidatedAt: data.lastValidatedAt ?? null,
    updatedAt: data.updatedAt ?? null,
  }
  apiKey.value = serviceBase
  secretKey.value = ''
}

function showError(error: unknown) {
  const parsed = readApiError(error)
  liveMessage.value = parsed.message
  fieldErrors.value = parsed.errors ?? {}
}

async function load() {
  const { data } = await api.get<CredentialStatus>('/api/settings/ocr-credentials')
  applyStatus(data)
}

onMounted(() => {
  load().catch(showError)
})

async function save() {
  liveMessage.value = ''
  fieldErrors.value = {}
  busy.value = true
  try {
    const { data } = await api.put<CredentialStatus>('/api/settings/ocr-credentials', {
      apiKey: apiKey.value,
      secretKey: secretKey.value,
    })
    applyStatus(data)
  } catch (error) {
    showError(error)
  } finally {
    busy.value = false
  }
}

async function testCredential() {
  liveMessage.value = ''
  fieldErrors.value = {}
  busy.value = true
  try {
    const { data } = await api.post<CredentialStatus>('/api/settings/ocr-credentials/test')
    applyStatus(data)
  } catch (error) {
    showError(error)
    secretKey.value = ''
  } finally {
    busy.value = false
  }
}

async function removeCredential() {
  liveMessage.value = ''
  fieldErrors.value = {}
  busy.value = true
  try {
    await api.delete('/api/settings/ocr-credentials')
    applyStatus({
      configured: false,
      apiKeyMask: null,
      validationStatus: null,
      lastValidatedAt: null,
      updatedAt: null,
    })
  } catch (error) {
    showError(error)
    secretKey.value = ''
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section>
    <header class="desk-head">
      <div>
        <p class="eyebrow">连接</p>
        <h2>OCR 凭证</h2>
      </div>
    </header>
    <div class="card settings-card">
    <p class="lede">识别使用星河大模型。服务域名填写 https://aistudio.baidu.com/llm/lmapi/v3，访问令牌从个人中心的访问令牌页复制。不要把令牌页网址填到服务域名。保存后只显示域名掩码和验证结果，访问令牌不会回填。</p>

    <dl v-if="status?.configured" class="status-list" aria-label="当前凭证">
      <div>
        <dt>服务域名</dt>
        <dd>{{ status.apiKeyMask }}</dd>
      </div>
      <div>
        <dt>验证状态</dt>
        <dd>{{ status.validationStatus }}</dd>
      </div>
      <div>
        <dt>最近验证</dt>
        <dd>
          <time :datetime="status.lastValidatedAt ?? undefined">{{ formatValidatedAt(status.lastValidatedAt) }}</time>
        </dd>
      </div>
    </dl>
    <p v-else class="muted">尚未配置 OCR 凭证。</p>

    <form @submit.prevent="save">
      <div class="settings-fields">
      <div class="field">
        <label for="api-key">服务域名</label>
        <input
          id="api-key"
          name="apiKey"
          type="url"
          autocomplete="off"
          required
          v-model="apiKey"
          :aria-describedby="fieldErrors.apiKey ? 'api-key-error' : undefined"
        />
        <p v-if="fieldErrors.apiKey" id="api-key-error" class="field-error">{{ fieldErrors.apiKey }}</p>
      </div>
      <div class="field">
        <label for="secret-key">访问令牌</label>
        <input
          id="secret-key"
          name="secretKey"
          type="password"
          autocomplete="off"
          required
          v-model="secretKey"
          :aria-describedby="fieldErrors.secretKey ? 'secret-key-error' : undefined"
        />
        <p v-if="fieldErrors.secretKey" id="secret-key-error" class="field-error">{{ fieldErrors.secretKey }}</p>
      </div>
      </div>
      <p class="live-error" aria-live="assertive">{{ liveMessage }}</p>
      <div class="actions">
        <button class="button-primary" type="submit" :disabled="busy">保存</button>
        <button class="button-quiet" type="button" data-action="test" :disabled="busy" @click="testCredential">
          测试连接
        </button>
        <button class="button-quiet" type="button" :disabled="busy" @click="removeCredential">删除凭证</button>
      </div>
    </form>
    </div>
  </section>
</template>
