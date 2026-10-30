const STATUS_LABELS: Record<string, string> = {
  QUEUED: '排队中',
  OCR_PROCESSING: '识别中',
  REVIEW_REQUIRED: '待复核',
  PASSED: '已通过',
  BLOCKED_DUPLICATE: '已拦截',
  FAILED: '处理失败',
  PROCESSING: '处理中',
  COMPLETED: '已完成',
}

const TYPE_LABELS: Record<string, string> = {
  VAT_SPECIAL: '增值税专用发票',
  VAT_ORDINARY: '增值税普通发票',
  ELECTRONIC: '电子发票',
  RAILWAY: '火车票',
  FLIGHT: '机票行程单',
  TAXI: '出租车票',
  FIXED_AMOUNT: '定额发票',
  CUSTOMS_PAYMENT: '海关缴款书',
  UNKNOWN: '未知票种',
}

const FIELD_LABELS: Record<string, string> = {
  invoiceCode: '发票代码',
  InvoiceCode: '发票代码',
  invoice_code: '发票代码',
  invoiceNumber: '发票号码',
  invoice_number: '发票号码',
  invoice_num: '发票号码',
  InvoiceNum: '发票号码',
  invoiceDate: '开票日期',
  InvoiceDate: '开票日期',
  start_date: '出发日期',
  amount: '金额',
  TotalAmount: '合计金额',
  tax: '税额',
  TotalTax: '合计税额',
  commodity_tax: '税额',
  total: '价税合计',
  AmountInFiguers: '价税合计',
  ticket_rates: '价税合计',
  invoice_rate_in_figure: '金额',
  checkCode: '校验码',
  sellerName: '销售方',
  SellerName: '销售方',
  sellerTaxId: '销售方税号',
  buyerName: '购买方',
  PurchaserName: '购买方',
  buyerTaxId: '购买方税号',
  passenger: '乘客',
  trainNumber: '车次',
  flightNumber: '航班号',
  ticketNumber: '客票号码',
  ticket_number: '电子客票号',
  ticket_num: '电子客票号',
  InvoiceType: '票面标题',
  invoiceType: '发票类型',
  fare: '票价',
  Fare: '票价',
  TotalFare: '合计金额',
  type: '识别类型',
  station_geton: '出发站',
  station_getoff: '到达站',
  exact_duplicate_key: '发票代码和号码',
}

const PROVIDER_TYPES: Record<string, string> = {
  vat_invoice: '增值税发票',
  train_ticket: '火车票',
  air_ticket: '机票行程单',
  taxi_receipt: '出租车票',
  quota_invoice: '定额发票',
  customs_payment: '海关缴款书',
}

export function statusLabel(status: string): string {
  return STATUS_LABELS[status] ?? status
}

export function invoiceTypeLabel(type: string): string {
  return TYPE_LABELS[type] ?? type
}

export function fieldLabel(name: string): string {
  return FIELD_LABELS[name] ?? name
}

export function fieldValue(name: string, value: string): string {
  if (name === 'type') {
    return PROVIDER_TYPES[value] ?? value
  }
  return value
}

export function issueMessage(issue: { code: string, field: string, message: string }): string {
  if (/[\u4e00-\u9fff]/.test(issue.message)) {
    return issue.message
  }
  const field = fieldLabel(issue.field)
  switch (issue.code) {
    case 'MISSING_REQUIRED_FIELD':
      return `缺少${field}`
    case 'INVALID_FORMAT':
      if (issue.field === 'invoiceCode' || issue.field === 'InvoiceCode') {
        return '发票代码应为 10 到 12 位数字'
      }
      if (issue.field === 'invoiceNumber' || issue.field === 'InvoiceNum') {
        return '发票号码应为 8 位数字'
      }
      return `${field}格式不正确`
    case 'AMOUNT_MISMATCH':
      return '金额加税额与价税合计不一致'
    case 'FUTURE_DATE':
      return '开票日期晚于今天'
    case 'IMPLAUSIBLY_OLD_DATE':
      return '开票日期超出合理年限'
    case 'LOW_CONFIDENCE':
      return `${field}识别置信度偏低`
    default:
      return issue.message
  }
}

export const INVOICE_TYPES = Object.keys(TYPE_LABELS)
