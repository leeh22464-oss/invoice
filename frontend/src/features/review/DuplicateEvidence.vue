<script setup lang="ts">
import type { DuplicateEvidence } from './types'

defineProps<{
  evidence: DuplicateEvidence | null
}>()
</script>

<template>
  <section data-duplicate class="duplicate-panel" aria-label="重复依据">
    <h3>重复依据</h3>
    <template v-if="evidence">
      <p :class="{ 'duplicate-exact': evidence.matchType === 'EXACT' }">
        {{ evidence.matchType === 'EXACT' ? '确定重复' : '疑似重复' }}
      </p>
      <p>已匹配票据 {{ evidence.matchedInvoiceId }}</p>
      <p>匹配分 {{ evidence.score }}</p>
      <p>命中字段 {{ evidence.matchedFields.join('、') }}</p>
    </template>
    <p v-else class="muted">无重复依据</p>
  </section>
</template>
