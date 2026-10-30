<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { api, readApiError } from '../../lib/api'
import { INVOICE_TYPES, invoiceTypeLabel, statusLabel } from '../../lib/labels'
import type { InvoiceRecord } from '../review/types'
import ExportMenu from './ExportMenu.vue'

const invoiceType = ref('')
const invoiceNumber = ref('')
const dateFrom = ref('')
const dateTo = ref('')
const rows = ref<InvoiceRecord[]>([])
const state = ref<'loading' | 'empty' | 'error' | 'ready'>('loading')
const liveMessage = ref('')
let requestSerial = 0

const query = computed(() => {
  const params = new URLSearchParams()
  if (invoiceType.value) {
    params.set('invoiceType', invoiceType.value)
  }
  if (invoiceNumber.value) {
    params.set('invoiceNumber', invoiceNumber.value)
  }
  if (dateFrom.value) {
    params.set('dateFrom', dateFrom.value)
  }
  if (dateTo.value) {
    params.set('dateTo', dateTo.value)
  }
  return params.toString()
})

function requestParams(): Record<string, string> {
  const params: Record<string, string> = {}
  if (invoiceType.value) {
    params.invoiceType = invoiceType.value
  }
  if (invoiceNumber.value) {
    params.invoiceNumber = invoiceNumber.value
  }
  if (dateFrom.value) {
    params.dateFrom = dateFrom.value
  }
  if (dateTo.value) {
    params.dateTo = dateTo.value
  }
  return params
}

async function load() {
  const serial = ++requestSerial
  state.value = 'loading'
  liveMessage.value = ''
  try {
    const { data } = await api.get<InvoiceRecord[]>('/api/ledger', { params: requestParams() })
    if (serial !== requestSerial) {
      return
    }
    rows.value = data.filter((row) => row.status === 'PASSED')
    state.value = rows.value.length === 0 ? 'empty' : 'ready'
  } catch (error) {
    if (serial !== requestSerial) {
      return
    }
    rows.value = []
    state.value = 'error'
    liveMessage.value = readApiError(error).message
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <section>
    <header class="desk-head">
      <div>
        <p class="eyebrow">报表</p>
        <h2>台账</h2>
      </div>
    </header>
    <div class="card ledger-card">
    <p class="lede">只显示已通过的票据。日期范围包含开始和结束当天。</p>
    <form class="ledger-filters" @change="load">
      <div class="field">
        <label for="filter-type">发票类型</label>
        <select id="filter-type" name="invoiceType" v-model="invoiceType">
          <option value="">全部</option>
          <option v-for="type in INVOICE_TYPES" :key="type" :value="type">{{ invoiceTypeLabel(type) }}</option>
        </select>
      </div>
      <div class="field">
        <label for="filter-number">发票号码</label>
        <input id="filter-number" name="invoiceNumber" type="text" v-model="invoiceNumber" />
      </div>
      <div class="field">
        <label for="filter-from">开始日期</label>
        <input id="filter-from" name="dateFrom" type="date" v-model="dateFrom" />
      </div>
      <div class="field">
        <label for="filter-to">结束日期</label>
        <input id="filter-to" name="dateTo" type="date" v-model="dateTo" />
      </div>
    </form>
    <ExportMenu :query="query" />
    <p v-if="state === 'loading'" class="ledger-loading" data-state="loading" role="status">正在加载台账</p>
    <p v-else-if="state === 'error'" class="ledger-error" data-state="error" role="alert" aria-live="assertive">
      {{ liveMessage }}
    </p>
    <p v-else-if="state === 'empty'" class="ledger-empty" data-state="empty">暂无已通过票据</p>
    <div v-else class="ledger-scroll">
      <table class="ledger-table" data-state="ready">
        <caption>已通过票据</caption>
        <thead>
          <tr>
            <th>发票类型</th>
            <th>发票代码</th>
            <th>发票号码</th>
            <th>开票日期</th>
            <th class="num">金额</th>
            <th class="num">税额</th>
            <th class="num">价税合计</th>
            <th>状态</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in rows" :key="row.id">
            <td>{{ invoiceTypeLabel(row.invoiceType) }}</td>
            <td>{{ row.invoiceCode }}</td>
            <td>{{ row.invoiceNumber }}</td>
            <td>{{ row.invoiceDate }}</td>
            <td class="num">{{ row.amount }}</td>
            <td class="num">{{ row.tax }}</td>
            <td class="num">{{ row.total }}</td>
            <td>{{ statusLabel(row.status) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    </div>
  </section>
</template>
