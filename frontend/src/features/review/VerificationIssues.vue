<script setup lang="ts">
import type { VerificationIssue } from './types'

defineProps<{
  issues: VerificationIssue[]
}>()
</script>

<template>
  <section data-issues class="issues-panel" aria-label="核验问题">
    <h3>核验问题</h3>
    <ul v-if="issues.length" class="issue-list">
      <li v-for="issue in issues" :key="`${issue.code}:${issue.field}`">
        {{ issue.message }}
      </li>
    </ul>
    <p v-else class="muted">暂无核验问题</p>
  </section>
</template>
